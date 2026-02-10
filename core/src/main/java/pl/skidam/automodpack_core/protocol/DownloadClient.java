package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import javax.net.ssl.*;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.utils.PlatformUtils;

public class DownloadClient implements AutoCloseable {

    private final List<Connection> connections = new ArrayList<>();
    private InetSocketAddress address = null;

    /**
     * Transports the connection and the specific SSLContext used to create it.
     * Required because the SSLContext may change dynamically during trust recovery.
     */
    private record InitialConnectionResult(PreValidationConnection connection, SSLContext sslContext) {}

    /**
     * Initializes the client by establishing a single "probe" connection to validate/recover SSL trust,
     * then hydrates the remaining connection pool in parallel using the validated SSL context.
     */
    public DownloadClient(Jsons.ModpackAddresses modpackAddresses, byte[] secretBytes, int poolSize, Function<X509Certificate, Boolean> trustedByUserCallback) throws IOException {
        if (poolSize < 1) throw new IllegalArgumentException("Pool size must be greater than 0");

        KeyStore keyStore = loadDefaultKeyStore();

        // Establish probe to handle potential SSL handshake errors (e.g., self-signed certs) sequentially before pooling.
        InitialConnectionResult probe = establishProbeConnection(modpackAddresses, keyStore, trustedByUserCallback);

        if (probe.connection.getSocket() != null && !probe.connection.getSocket().isClosed()) {
            if (secretBytes == null) {
                probe.connection().getSocket().close();
            } else {
                connections.add(new Connection(probe.connection, secretBytes));
            }
        }

        if (secretBytes == null) {
            return;
        }

        int remainingNeeded = poolSize - connections.size();
        if (remainingNeeded < 1) {
            return;
        }

        // Parallel pool hydration using the session-aware SSLContext from the probe.
        List<Connection> newConnections = IntStream.range(0, remainingNeeded)
                .parallel()
                .mapToObj(i -> {
                    try {
                        return new Connection(getPreValidationConnection(modpackAddresses, probe.sslContext), secretBytes);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                })
                .toList();

        connections.addAll(newConnections);
        LOGGER.info("Download client initialized with {} connections to {}", connections.size(), modpackAddresses.hostAddress);
    }

    /**
     * Attempts a connection with a capturing trust manager. If the handshake fails due to an
     * untrusted certificate, the chain is captured, presented to the user callback, and the connection is retried.
     */
    private InitialConnectionResult establishProbeConnection(Jsons.ModpackAddresses addresses, KeyStore keyStore, Function<X509Certificate, Boolean> trustCallback) throws IOException {
        AtomicReference<X509Certificate[]> capturedChain = new AtomicReference<>();
        SSLContext context = createSSLContext(keyStore, capturedChain::set);

        try {
            PreValidationConnection conn = getPreValidationConnection(addresses, context);
            return new InitialConnectionResult(conn, context);
        } catch (IOException e) { // Inavlid/Selfsigned certificate, prompt user for trust.
            return recoverProbeConnection(e, addresses, keyStore, trustCallback, capturedChain.get());
        }
    }

    private InitialConnectionResult recoverProbeConnection(IOException originalError, Jsons.ModpackAddresses addresses, KeyStore keyStore, Function<X509Certificate, Boolean> trustCallback, X509Certificate[] chain) throws IOException {
        if (chain == null || chain.length == 0 || trustCallback == null) {
            throw originalError;
        }

        boolean isTrusted = trustCallback.apply(chain[0]);
        if (!isTrusted) {
            throw new IOException("User rejected the certificate.", originalError);
        }

        try {
            keyStore.setCertificateEntry(addresses.hostAddress.getHostString(), chain[0]);

            // Re-initialize context with the updated KeyStore containing the user-trusted cert.
            SSLContext trustedContext = createSSLContext(keyStore, null);

            PreValidationConnection retryConn = getPreValidationConnection(addresses, trustedContext);
            return new InitialConnectionResult(retryConn, trustedContext);

        } catch (KeyStoreException kse) {
            throw new IOException("Failed to update KeyStore with trusted certificate", kse);
        }
    }

    private KeyStore loadDefaultKeyStore() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null);
            return keyStore;
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
            throw new RuntimeException("Failed to initialize KeyStore", e);
        }
    }

    private PreValidationConnection getPreValidationConnection(Jsons.ModpackAddresses modpackAddresses, SSLContext sharedContext) throws IOException {
        String hostName = modpackAddresses.hostAddress.getHostString();
        if (address == null) {
            address = new InetSocketAddress(hostName, modpackAddresses.hostAddress.getPort());
            if (address.isUnresolved()) {
                throw new IOException("Failed to resolve host address: " + hostName);
            }
        }
        return new PreValidationConnection(address, modpackAddresses, sharedContext);
    }

    /**
     * Configures SSL context with TLSv1.3 and a custom TrustManager.
     * @param onValidating Optional callback to capture certificate chains during handshake failures.
     */
    private SSLContext createSSLContext(KeyStore trustedCertificates, Consumer<X509Certificate[]> onValidating) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
            X509ExtendedTrustManager trustManager = new CustomizableTrustManager(trustedCertificates, onValidating);

            sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());

            SSLSessionContext sessionContext = sslContext.getClientSessionContext();
            sessionContext.setSessionTimeout(1800);
            sessionContext.setSessionCacheSize(20);

            return sslContext;
        } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
            throw new RuntimeException("Failed to initialize SSLContext", e);
        }
    }

    public static DownloadClient tryCreate(Jsons.ModpackAddresses modpackAddresses, byte[] secretBytes, int poolSize, Function<X509Certificate, Boolean> trustedByUserCallback) {
        try {
            return new DownloadClient(modpackAddresses, secretBytes, poolSize, trustedByUserCallback);
        } catch (IOException e) {
            LOGGER.error("Failed to create download client: {}", e.getMessage());
            LOGGER.debug(e);
            return null;
        }
    }

    /**
     * Recursively searches for an idle connection in the pool.
     * Marks the found connection as busy to prevent race conditions.
     */
    private synchronized Connection getFreeConnection() {
        Iterator<Connection> iterator = connections.iterator();
        while (iterator.hasNext()) {
            Connection conn = iterator.next();
            if (!conn.isBusy()) {
                if (!conn.isActive()) {
                    iterator.remove();
                    return getFreeConnection();
                }
                conn.setBusy(true);
                return conn;
            }
        }
        throw new IllegalStateException("No available connections");
    }

    public CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback) {
        return getFreeConnection().sendDownloadFile(fileHash, destination, chunkCallback);
    }

    public CompletableFuture<Path> requestRefresh(byte[][] fileHashes, Path destination) {
        return getFreeConnection().sendRefreshRequest(fileHashes, destination);
    }

    @Override
    public void close() {
        for (Connection conn : connections) {
            conn.close();
        }
        connections.clear();
    }
}

/**
 * Handles the initial TCP connection and protocol-specific handshakes (Magic)
 * prior to or during the SSL upgrade.
 */
class PreValidationConnection {

    private final SSLSocket socket;

    public PreValidationConnection(InetSocketAddress resolvedHostAddress, Jsons.ModpackAddresses modpackAddresses, SSLContext sslContext) throws IOException {
        Socket plainSocket = new Socket();
        plainSocket.connect(resolvedHostAddress, 10000);
        plainSocket.setSoTimeout(10000);

        // Perform custom "Magic" handshake over plain text if required by config.
        if (modpackAddresses.requiresMagic) {
            try {
                DataOutputStream plainOut = new DataOutputStream(new BufferedOutputStream(plainSocket.getOutputStream()));
                DataInputStream plainIn = new DataInputStream(new BufferedInputStream(plainSocket.getInputStream()));

                byte[] hostBytes = resolvedHostAddress.getHostString().getBytes(StandardCharsets.UTF_8);

                plainOut.writeInt(MAGIC_AMMH);
                plainOut.writeShort(hostBytes.length);
                plainOut.write(hostBytes);
                plainOut.flush();

                int handshakeResponse = plainIn.readInt();
                if (handshakeResponse != MAGIC_AMOK) {
                    throw new IOException("Invalid response from server: " + handshakeResponse);
                }
            } catch (IOException e) {
                try { plainSocket.close(); } catch (IOException ignored) {}
                throw e;
            }
        }

        // Layer SSL over the existing socket.
        SSLSocketFactory factory = sslContext.getSocketFactory();
        SSLSocket sslSocket = (SSLSocket) factory.createSocket(plainSocket, resolvedHostAddress.getHostString(), resolvedHostAddress.getPort(), true);

        sslSocket.setEnabledProtocols(new String[]{"TLSv1.3"});
        sslSocket.setEnabledCipherSuites(new String[]{"TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"});

        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        sslSocket.setSSLParameters(sslParameters);

        try {
            sslSocket.startHandshake();
        } catch (IOException e) {
            try { sslSocket.close(); } catch (IOException ignored) {}
            throw e;
        }

        this.socket = sslSocket;
    }

    protected SSLSocket getSocket() {
        return socket;
    }
}

/**
 * Manages an active, authenticated session. Handles protocol negotiation,
 * framing, compression, and async I/O.
 */
class Connection implements AutoCloseable {

    private byte protocolVersion = LATEST_SUPPORTED_PROTOCOL_VERSION;
    private byte compressionType = COMPRESSION_ZSTD;
    private int chunkSize = DEFAULT_CHUNK_SIZE;
    private final byte[] secretBytes;
    private final SSLSocket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    // Reuse this buffer for reading from socket to avoid allocation per frame.
    // Size = Default Chunk + Header overhead (approx) + Safety margin
    private final byte[] networkInputBuffer = new byte[MAX_CHUNK_SIZE + 8192];

    public Connection(PreValidationConnection preValidationConnection, byte[] secretBytes) throws IOException {
        if (preValidationConnection.getSocket() == null || preValidationConnection.getSocket().isClosed()) {
            throw new SSLHandshakeException("Server certificate invalid, connection closed");
        }
        this.socket = preValidationConnection.getSocket();
        this.secretBytes = secretBytes;

        this.in = new DataInputStream(new BufferedInputStream(this.socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(this.socket.getOutputStream()));

        // Negotiate connection parameters sequentially.
        try {
            if (!PlatformUtils.canUseZstd()) {
                this.compressionType = COMPRESSION_GZIP;
            }
            this.compressionType = sendCompressionConfig(compressionType);
            this.chunkSize = sendChunkSizeConfig(DEFAULT_CHUNK_SIZE);
            sendEchoConfig();
        } catch (IOException e) {
            LOGGER.error("Failed to configure connection", e);
            throw e;
        }
    }

    public boolean isActive() {
        return !socket.isClosed();
    }

    public boolean isBusy() {
        return busy.get();
    }

    public void setBusy(boolean value) {
        busy.set(value);
    }

    private CompressionCodec getCompressionCodec() {
        return CompressionFactory.getCodec(compressionType);
    }

    public CompletableFuture<Path> sendDownloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback) {
        if (destination == null) throw new IllegalArgumentException("Destination cannot be null");

        return CompletableFuture.supplyAsync(() -> {
            Exception exception = null;
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(64 + fileHash.length);
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeByte(protocolVersion);
                dos.writeByte(FILE_REQUEST_TYPE);
                dos.write(secretBytes);
                dos.writeInt(fileHash.length);
                dos.write(fileHash);

                writeProtocolMessage(baos.toByteArray());
                return readFileResponse(destination, chunkCallback);
            } catch (Exception e) {
                exception = e;
                throw new CompletionException(e);
            } finally {
                finalBlock(exception);
            }
        }, executor);
    }

    public CompletableFuture<Path> sendRefreshRequest(byte[][] fileHashes, Path destination) {
        return CompletableFuture.supplyAsync(() -> {
            Exception exception = null;
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeByte(protocolVersion);
                dos.writeByte(REFRESH_REQUEST_TYPE);
                dos.write(secretBytes);
                dos.writeInt(fileHashes.length);
                if (fileHashes.length > 0) {
                    dos.writeInt(fileHashes[0].length);
                    for (byte[] hash : fileHashes) {
                        dos.write(hash);
                    }
                }

                writeProtocolMessage(baos.toByteArray());
                return readFileResponse(destination, null);
            } catch (Exception e) {
                exception = e;
                throw new CompletionException(e);
            } finally {
                finalBlock(exception);
            }
        }, executor);
    }

    /**
     * Cleans up input stream and releases the busy flag upon completion.
     */
    private void finalBlock(Exception exception) {
        try {
            int available;
            while ((available = in.available()) > 0) {
                in.skipBytes(available);
            }
        } catch (IOException e) {
            if (exception == null) throw new CompletionException(e);
        } finally {
            if (exception == null) setBusy(false);
        }
    }

    /**
     * Segments payload into chunks, compresses them, and sends with protocol framing.
     */
    private void writeProtocolMessage(byte[] payload) throws IOException {
        CompressionCodec codec = getCompressionCodec();
        int offset = 0;

        while (offset < payload.length) {
            int bytesToSend = Math.min(payload.length - offset, this.chunkSize);
            byte[] chunk = new byte[bytesToSend];
            System.arraycopy(payload, offset, chunk, 0, bytesToSend);

            byte[] compressedChunk = codec.compress(chunk);
            out.writeInt(compressedChunk.length);
            out.writeInt(chunk.length);
            out.write(compressedChunk);

            offset += bytesToSend;
        }
        out.flush();
    }

    /**
     * Reads a framing header (Compressed Len + Original Len) and returns decompressed data.
     */
    private byte[] readProtocolMessageFrame() throws IOException {
        int compressedLength = in.readInt();
        int originalLength = in.readInt();

        int maxAllowedSize = this.chunkSize + 8192; // Allow overhead buffer

        if (compressedLength < 0 || compressedLength > maxAllowedSize) {
            throw new IOException("Frame compressed length (" + compressedLength + ") exceeds limit (" + maxAllowedSize + ")");
        }

        if (originalLength < 0 || originalLength > this.chunkSize) {
            throw new IOException("Frame original length (" + originalLength + ") exceeds chunk size (" + this.chunkSize + ")");
        }

        if (compressedLength > networkInputBuffer.length) {
            throw new IOException("Compressed length exceeds buffer capacity");
        }

        in.readFully(networkInputBuffer, 0, compressedLength);

        return getCompressionCodec().decompress(networkInputBuffer, 0, compressedLength, originalLength);
    }

    /**
     * Processes the server response stream. Expects Header -> Data Frames -> EOT.
     */
    private Path readFileResponse(Path destination, IntConsumer chunkCallback) throws IOException {
        byte[] headerData = readProtocolMessageFrame();
        ByteBuffer headerWrap = ByteBuffer.wrap(headerData);

        byte version = headerWrap.get();
        byte messageType = headerWrap.get();

        if (messageType == ERROR) {
            int errLen = headerWrap.getInt();
            byte[] errBytes = new byte[errLen];
            headerWrap.get(errBytes);
            throw new IOException("Server error: " + new String(errBytes, StandardCharsets.UTF_8));
        }

        if (messageType == END_OF_TRANSMISSION) {
            return destination;
        }

        if (messageType != FILE_RESPONSE_TYPE) {
            throw new IOException("Unexpected message type: " + messageType);
        }

        long expectedFileSize = headerWrap.getLong();
        long receivedBytes = 0;

        try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(destination,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE))) {

            while (receivedBytes < expectedFileSize) {
                byte[] dataFrame = readProtocolMessageFrame();
                int toWrite = Math.min(dataFrame.length, (int) (expectedFileSize - receivedBytes));
                fos.write(dataFrame, 0, toWrite);
                receivedBytes += toWrite;
                if (chunkCallback != null) chunkCallback.accept(toWrite);
            }
        }

        byte[] eotData = readProtocolMessageFrame();
        if (eotData.length < 2 || eotData[0] != version || eotData[1] != END_OF_TRANSMISSION) {
            throw new IOException("Invalid EOT frame");
        }
        return destination;
    }

    private byte sendCompressionConfig(byte desiredCompression) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeByte(protocolVersion);
        dos.writeByte(CONFIGURATION_COMPRESSION_TYPE);
        dos.writeByte(desiredCompression);

        out.write(baos.toByteArray());
        out.flush();

        byte version = in.readByte();
        if (version >= 1 && version < protocolVersion) {
            protocolVersion = version;
        }

        byte type = in.readByte();
        if (type != CONFIGURATION_COMPRESSION_TYPE) throw new IOException("Unexpected response: " + type);

        byte negotiated = in.readByte();
        if (negotiated != COMPRESSION_NONE && negotiated != COMPRESSION_ZSTD && negotiated != COMPRESSION_GZIP) {
            throw new IOException("Unsupported compression: " + negotiated);
        }
        return negotiated;
    }

    private int sendChunkSizeConfig(int desiredChunkSize) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeByte(protocolVersion);
        dos.writeByte(CONFIGURATION_CHUNK_SIZE_TYPE);
        dos.writeInt(desiredChunkSize);

        out.write(baos.toByteArray());
        out.flush();

        byte version = in.readByte();
        if (version >= 1 && version < protocolVersion) {
            protocolVersion = version;
        }

        byte type = in.readByte();
        if (type != CONFIGURATION_CHUNK_SIZE_TYPE) throw new IOException("Unexpected response: " + type);

        int negotiated = in.readInt();
        if (negotiated < MIN_CHUNK_SIZE || negotiated > MAX_CHUNK_SIZE) {
            throw new IOException("Chunk size out of bounds: " + negotiated);
        }
        return negotiated;
    }

    private void sendEchoConfig() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeByte(protocolVersion);
        dos.writeByte(CONFIGURATION_ECHO_TYPE);
        out.write(baos.toByteArray());
        out.flush();
    }

    @Override
    public void close() {
        try { socket.close(); } catch (Exception ignored) {}
        executor.shutdownNow();
    }
}