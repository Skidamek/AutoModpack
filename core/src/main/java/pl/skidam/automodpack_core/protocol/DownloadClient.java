package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import javax.net.ssl.*;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;

/**
 * A DownloadClient that creates a pool of connections.
 * Each connection performs an initial plain-text handshake by sending the AMMC magic,
 * waiting for the AMOK reply, and then upgrading the same socket to TLSv1.3.
 * Subsequent protocol messages are framed and compressed (using Zstd).
 */
public class DownloadClient implements AutoCloseable {

    private final List<Connection> connections = new ArrayList<>();
    private InetSocketAddress address = null;

    /**
     * Creates a new {@link DownloadClient} for the specified address. If the first connection fails with a verification
     * error on a self-signed certificate, {@code trustedByUserCallback} is executed to determine whether the
     * certificate should be trusted anyway.
     *
     * @param modpackAddresses      the object containing host and server addresses
     * @param secretBytes           the secret bytes obtained from the server
     * @param poolSize              the number of connections
     * @param trustedByUserCallback the callback to determine whether a certificate should be trusted
     */
    public DownloadClient(Jsons.ModpackAddresses modpackAddresses, byte[] secretBytes, int poolSize, Function<X509Certificate, Boolean> trustedByUserCallback) throws IOException {
        KeyStore keyStore;
        if (poolSize < 1) {
            throw new IllegalArgumentException("Pool size must be greater than 0");
        }

        try {
            keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        } catch (KeyStoreException e) {
            throw new RuntimeException("Failed to create a KeyStore of the default type.", e);
        }
        try {
            keyStore.load(null);
        } catch (NoSuchAlgorithmException | CertificateException e) {
            throw new RuntimeException("Failed to load empty KeyStore", e);
        }

        PreValidationConnection firstConnection = getPreValidationConnection(modpackAddresses, keyStore);
        if (firstConnection.getSocket() != null && !firstConnection.getSocket().isClosed() && firstConnection.getUnvalidatedCertificate() == null && secretBytes != null) {
            connections.add(new Connection(firstConnection, secretBytes));
        } else if (firstConnection.getSocket() != null) {
            firstConnection.getSocket().close();
        }

        if (trustedByUserCallback != null && firstConnection.getUnvalidatedCertificate() != null && trustedByUserCallback.apply(firstConnection.getUnvalidatedCertificate())) {
            try {
                keyStore.setCertificateEntry(modpackAddresses.hostAddress.getHostString(), firstConnection.getUnvalidatedCertificate());
            } catch (KeyStoreException e) {
                throw new RuntimeException("Could not add the trusted certificate to the KeyStore.", e);
            }
        }

        if (secretBytes == null) {
            return;
        }

        for (int i = connections.size(); i < poolSize; i++) {
            PreValidationConnection preValidationConnection = getPreValidationConnection(modpackAddresses, keyStore);
            connections.add(new Connection(preValidationConnection, secretBytes));
        }
    }

    private PreValidationConnection getPreValidationConnection(Jsons.ModpackAddresses modpackAddresses, KeyStore keyStore) throws IOException {
        PreValidationConnection preValidationConnection;
        try {
            String hostName = modpackAddresses.hostAddress.getHostString();
            if (address == null) {
                InetSocketAddress resolvedInetSocketAddress = new InetSocketAddress(hostName, modpackAddresses.hostAddress.getPort());
                if (resolvedInetSocketAddress.isUnresolved()) {
                    throw new IOException("Failed to resolve host address: " + hostName);
                }
                address = resolvedInetSocketAddress;
            }

            preValidationConnection = new PreValidationConnection(address, modpackAddresses, keyStore);
        } catch (KeyStoreException e) {
            throw new RuntimeException("Failed to establish connection due to an issue with the generated KeyStore.", e);
        }
        return preValidationConnection;
    }

    /**
     * Tries to create a new {@link DownloadClient}, logging an error if the operation fails.
     *
     * @param modpackAddresses      the object containing host and server addresses
     * @param secretBytes           the secret bytes obtained from the server
     * @param poolSize              the number of connections
     * @param trustedByUserCallback the callback to determine whether a certificate should be trusted
     * @return the {@link DownloadClient} on success or {@code null} on failure
     * @see DownloadClient#DownloadClient(Jsons.ModpackAddresses, byte[], int, Function) DownloadClient
     */
    public static DownloadClient tryCreate(Jsons.ModpackAddresses modpackAddresses, byte[] secretBytes, int poolSize, Function<X509Certificate, Boolean> trustedByUserCallback) {
        try {
            return new DownloadClient(modpackAddresses, secretBytes, poolSize, trustedByUserCallback);
        } catch (IOException e) {
            LOGGER.error("Failed to create a download client. Error: {}", e.getMessage());
            LOGGER.debug(e);
            return null;
        }
    }

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

    /**
     * Downloads a file identified by its SHA-1 hash to the given destination.
     * Returns a CompletableFuture that completes when the download finishes.
     */
    public CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback) {
        Connection conn = getFreeConnection();
        return conn.sendDownloadFile(fileHash, destination, chunkCallback);
    }

    /**
     * Sends a refresh request with the given file hashes.
     */
    public CompletableFuture<Path> requestRefresh(byte[][] fileHashes, Path destination) {
        Connection conn = getFreeConnection();
        return conn.sendRefreshRequest(fileHashes, destination);
    }

    /**
     * Closes all connections.
     */
    @Override
    public void close() {
        for (Connection conn : connections) {
            conn.close();
        }

        connections.clear();
    }
}

/**
 * A helper class for connecting to the server that allows for retrieving certificates that failed verification.
 * It first performs a plain-text handshake then upgrades the same socket to TLS.
 */
class PreValidationConnection {

    private final SSLSocket socket;
    private final X509Certificate unvalidatedCertificate;

    /**
     * Creates a new connection by first opening a plain TCP socket,
     * sending the AMMC magic, waiting for the AMOK reply, and then upgrading to TLS.
     *
     * @param modpackAddresses the object containing host and server addresses
     * @param keyStore         the keystore containing trusted certificates
     */
    public PreValidationConnection(InetSocketAddress resolvedHostAddress, Jsons.ModpackAddresses modpackAddresses, KeyStore keyStore) throws IOException, KeyStoreException {
        // Step 1. Create a plain TCP connection.
        Socket plainSocket = new Socket();
        plainSocket.connect(resolvedHostAddress, 10000); // To create socket, we need to pass a resolved socket address
        plainSocket.setSoTimeout(10000);

        if (modpackAddresses.requiresMagic) {
            try {
                DataOutputStream plainOut = new DataOutputStream(plainSocket.getOutputStream());
                DataInputStream plainIn = new DataInputStream(plainSocket.getInputStream());

                // Step 2. Send the handshake (AMMC magic) over the plain socket.
                plainOut.writeInt(MAGIC_AMMC);
                plainOut.flush();

                LOGGER.debug("Sent AutoModpack magic handshake to {} - magic: {}", resolvedHostAddress, MAGIC_AMMC);

                // Step 3. Wait for the server's reply (AMOK magic).
                int handshakeResponse = plainIn.readInt();
                if (handshakeResponse != MAGIC_AMOK) {
                    throw new IOException("Invalid response from server: " + handshakeResponse);
                }

                LOGGER.debug("Received AutoModpack magic handshake response from {} - readable bytes {}", resolvedHostAddress, plainIn.available());
            } catch (IOException e) {
                LOGGER.error("AutoModpack magic handshake failed", e);
                plainSocket.close();
            }
        }

        if (plainSocket.isClosed() || !plainSocket.isConnected()) {
            throw new IOException("Failed to establish a plain socket connection to " + resolvedHostAddress);
        }

        // Step 4. Upgrade the plain socket to TLS using the same underlying connection.
        AtomicReference<X509Certificate[]> interceptedCertificateChain = new AtomicReference<>();

        SSLContext context = createSSLContext(keyStore, interceptedCertificateChain::set);
        SSLSocketFactory factory = context.getSocketFactory();
        // The createSocket(Socket, host, port, autoClose) wraps the existing plain socket.
        SSLSocket sslSocket = (SSLSocket) factory.createSocket(plainSocket, resolvedHostAddress.getHostString(), resolvedHostAddress.getPort(), true);
        sslSocket.setEnabledProtocols(new String[]{"TLSv1.3"});
        sslSocket.setEnabledCipherSuites(new String[]{"TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"});
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        sslSocket.setSSLParameters(sslParameters);

        SSLSession session = sslSocket.getSession();
        X509Certificate certificate = null;
        X509Certificate unvalidatedCertificate = null;
        X509Certificate[] serverCertificateChain = interceptedCertificateChain.get();
        if (serverCertificateChain != null && serverCertificateChain.length > 0) {
            certificate = serverCertificateChain[0];
        }

        if (certificate == null) {
            throw new IOException("No certificate found in server's response");
        }

        if (!session.isValid()) { // Handshake failed
            sslSocket.close();
            unvalidatedCertificate = certificate;
        }

        // Check if its valid CA signed certificate for the host domain
        if (!isSelfSigned(certificate)) {
            if (!session.isValid()) {
                String modpackHostDomain = modpackAddresses.hostAddress.getHostString();
                LOGGER.error("Received a CA-signed certificate, but the TLS handshake failed. This is most likely because the certificate could not be verified against the modpack host domain: {}", modpackHostDomain);
            } else {
                // Verify that this certificate is also valid for the minecraft server domain
                DefaultHostnameVerifier hostnameVerifier = new DefaultHostnameVerifier();
                String minecraftServerDomain = modpackAddresses.serverAddress.getHostString();
                if (!hostnameVerifier.verify(minecraftServerDomain, session)) {
                    sslSocket.close();
                    unvalidatedCertificate = certificate;
                    LOGGER.error("Received a CA-signed certificate, but the TLS handshake failed. This is most likely because the certificate could not be verified against the Minecraft server domain: {}", minecraftServerDomain);
                } else {
                    LOGGER.info("Signed certificate validation succeeded for {}", minecraftServerDomain);
                }
            }
        }

        // The unvalidatedCertificate will be non-null if the certificate failed automated validation and requires manual verification by the client.
        // If null, the certificate was validated successfully.
        this.unvalidatedCertificate = unvalidatedCertificate;
        this.socket = sslSocket;
    }

    private static boolean isSelfSigned(X509Certificate certificate) {
        try {
            certificate.verify(certificate.getPublicKey());
        } catch (CertificateException | NoSuchAlgorithmException | SignatureException | InvalidKeyException | NoSuchProviderException e) {
            return false;
        }
        return true;
    }

    protected SSLSocket getSocket() {
        return socket;
    }

    public X509Certificate getUnvalidatedCertificate() {
        return unvalidatedCertificate;
    }

    /**
     * Creates an SSLContext that trusts the certificates in {@code trustedCertificates}, and verifies certificates
     * using the default key store otherwise.
     *
     * @param trustedCertificates a {@link KeyStore} containing certificates to be trusted
     * @param onValidating        a callback that is run for every certificate chain before verification
     */
    private SSLContext createSSLContext(KeyStore trustedCertificates, Consumer<X509Certificate[]> onValidating)
            throws KeyStoreException {
        SSLContext sslContext;
        try {
            sslContext = SSLContext.getInstance("TLSv1.3");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("TLS 1.3 is not supported", e);
        }
        X509ExtendedTrustManager trustManager = new CustomizableTrustManager(trustedCertificates, onValidating);
        try {
            sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());
        } catch (KeyManagementException e) {
            throw new RuntimeException("Failed to initialize SSLContext", e);
        }
        return sslContext;
    }
}

/**
 * A helper class representing a single connection.
 * Outbound messages are compressed and framed; inbound frames are decompressed and processed.
 * The compression codec is lazily loaded only when compression is actually used.
 */
class Connection implements AutoCloseable {

    private byte protocolVersion = PROTOCOL_VERSION;
    private byte compressionType = COMPRESSION_ZSTD;
    private int chunkSize = DEFAULT_CHUNK_SIZE;
    private final byte[] secretBytes;
    private final SSLSocket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public boolean isActive() {
        return !socket.isClosed();
    }

    /**
     * Creates a new connection from a successful {@link PreValidationConnection} instance. Throws an exception if the
     * handshake of the {@link PreValidationConnection} has failed.
     */
    public Connection(PreValidationConnection preValidationConnection, byte[] secretBytes) throws IOException {
        if (preValidationConnection.getSocket() == null || preValidationConnection.getSocket().isClosed()) {
            throw new SSLHandshakeException("Server certificate is not valid, connection got closed");
        }
        this.socket = preValidationConnection.getSocket();
        this.secretBytes = secretBytes;

        try {
            // Now use the SSL socket for further communication.
            this.in = new DataInputStream(this.socket.getInputStream());
            this.out = new DataOutputStream(this.socket.getOutputStream());
            LOGGER.debug("Connection established with: {}", this.socket.getInetAddress().getHostAddress());
        } catch (IOException e) {
            throw new IOException("Failed to establish connection", e);
        }

        try {
            if (!getCompressionCodec().isInitialized()) {
                LOGGER.warn("Desired compression codec failed to initialize, falling back to Gzip");
                this.compressionType = COMPRESSION_GZIP;
            }
            this.compressionType = sendCompressionConfig(compressionType);
            this.chunkSize = sendChunkSizeConfig(DEFAULT_CHUNK_SIZE);
            sendEchoConfig();
            LOGGER.debug("Connection configuration completed, protocolVersion: {} compression: {}, chunk size: {}", this.protocolVersion, this.compressionType, this.chunkSize);
        } catch (IOException e) {
            LOGGER.error("Failed to exchange configuration details, using defaults", e);
        }
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

    /**
     * Sends a file request over this connection.
     */
    public CompletableFuture<Path> sendDownloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback) {
        if (destination == null) {
            throw new IllegalArgumentException("Destination cannot be null");
        }

        return CompletableFuture.supplyAsync(() -> {
            Exception exception = null;
            try {
                // Build File Request message:
                // [protocolVersion][FILE_REQUEST_TYPE][secret][int: fileHash.length][fileHash]
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeByte(protocolVersion);
                dos.writeByte(FILE_REQUEST_TYPE);
                dos.write(secretBytes);
                dos.writeInt(fileHash.length);
                dos.write(fileHash);
                dos.flush();
                byte[] payload = baos.toByteArray();

                writeProtocolMessage(payload);
                return readFileResponse(destination, chunkCallback);
            } catch (Exception e) {
                exception = e;
                throw new CompletionException(e);
            } finally {
                finalBlock(exception);
            }
        }, executor);
    }

    /**
     * Sends a refresh request over this connection.
     */
    public CompletableFuture<Path> sendRefreshRequest(byte[][] fileHashes, Path destination) {
        return CompletableFuture.supplyAsync(() -> {
            Exception exception = null;
            try {
                // Build Refresh Request message:
                // [protocolVersion][REFRESH_REQUEST_TYPE][secret][int: fileHashesCount]
                // [int: fileHashLength] then each file hash.
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeByte(protocolVersion);
                dos.writeByte(REFRESH_REQUEST_TYPE);
                dos.write(secretBytes);
                dos.writeInt(fileHashes.length);
                if (fileHashes.length > 0) {
                    dos.writeInt(fileHashes[0].length); // assuming all hashes have same length
                    for (byte[] hash : fileHashes) {
                        dos.write(hash);
                    }
                }
                dos.flush();
                byte[] payload = baos.toByteArray();

                writeProtocolMessage(payload);
                return readFileResponse(destination, null);
            } catch (Exception e) {
                exception = e;
                throw new CompletionException(e);
            } finally {
                finalBlock(exception);
            }
        }, executor);
    }

    private void finalBlock(Exception exception) {
        // skip any remaining data
        try {
            while (in.available() > 0) {
                in.skipBytes(in.available());
            }
        } catch (IOException e) {
            if (exception == null) {
                exception = e;
                throw new CompletionException(e);
            }
        } finally {
            if (exception == null) {
                setBusy(false);
            }
        }
    }

    /**
     * Compresses (if enabled) and writes a protocol message using the negotiated compression algorithm in chunks.
     * Each chunk is framed individually.
     * Framing without compression: [int: chunkLength][chunk payload].
     * Framing with compression: [int: compressedChunkLength][int: originalChunkLength][compressed chunk payload].
     */
    private void writeProtocolMessage(byte[] payload) throws IOException {
        int offset = 0;
        while (offset < payload.length) {
            int bytesToSend = Math.min(payload.length - offset, this.chunkSize);
            byte[] chunk = new byte[bytesToSend];
            System.arraycopy(payload, offset, chunk, 0, bytesToSend);
            byte[] compressedChunk = getCompressionCodec().compress(chunk);
            out.writeInt(compressedChunk.length);
            out.writeInt(chunk.length);
            out.write(compressedChunk);
            offset += bytesToSend;
        }
        out.flush();
    }

    /**
     * Reads one framed protocol message, decompressing it with the negotiated compression algorithm.
     */
    private byte[] readProtocolMessageFrame() throws IOException {
        int compressedLength = in.readInt();
        int originalLength = in.readInt();

        if (compressedLength < 0 || originalLength < 0) {
            throw new IllegalArgumentException("Invalid compressed or original length");
        }

        if (originalLength > this.chunkSize) {
            throw new IllegalArgumentException("Original length exceeds maximum packet size");
        }

        byte[] compressed = new byte[compressedLength];
        in.readFully(compressed);

        return getCompressionCodec().decompress(compressed, originalLength);
    }

    /**
     * Processes a file/refresh response according to your protocol.
     * The response is expected to have:
     * - A header frame: [protocolVersion][messageType][(if FILE_RESPONSE_TYPE) long expectedFileSize]
     * - One or more data frames containing file data until the total file size is reached.
     * - A final frame: [protocolVersion][END_OF_TRANSMISSION]
     */
    private Path readFileResponse(Path destination, IntConsumer chunkCallback) throws IOException {
        // Header frame
        byte[] headerFrame = readProtocolMessageFrame();
        try (DataInputStream headerIn = new DataInputStream(new ByteArrayInputStream(headerFrame))) {
            byte version = headerIn.readByte();
            byte messageType = headerIn.readByte();

            if (messageType == ERROR) {
                int errLen = headerIn.readInt();
                byte[] errBytes = new byte[errLen];
                headerIn.readFully(errBytes);
                throw new IOException("Server error: " + new String(errBytes));
            }

            long receivedBytes = 0;
            OutputStream fos = new FileOutputStream(destination.toFile());

            if (messageType == END_OF_TRANSMISSION) {
                fos.close();
                return destination;
            }

            if (messageType != FILE_RESPONSE_TYPE) {
                fos.close();
                throw new IOException("Unexpected message type: " + messageType);
            }

            long expectedFileSize = headerIn.readLong();

            // Read data frames until the expected file size is received.
            while (receivedBytes < expectedFileSize) {
                byte[] dataFrame = readProtocolMessageFrame();
                int toWrite = Math.min(dataFrame.length, (int) (expectedFileSize - receivedBytes));

                fos.write(dataFrame, 0, toWrite);
                receivedBytes += toWrite;

                if (chunkCallback != null) {
                    chunkCallback.accept(toWrite);
                }
            }

            fos.close();

            // Read EOT frame
            byte[] eotFrame = readProtocolMessageFrame();
            try (DataInputStream eotIn = new DataInputStream(new ByteArrayInputStream(eotFrame))) {
                byte ver = eotIn.readByte();
                byte eotType = eotIn.readByte();

                if (ver != version || eotType != END_OF_TRANSMISSION) {
                    throw new IOException("Invalid end-of-transmission marker. Expected version " + version + " and type " + END_OF_TRANSMISSION + ", got version " + ver + " and type " + eotType);
                }
            }

            return destination;
        }
    }

    /**
     * Sends the configuration packet to negotiate compression.
     */
    public byte sendCompressionConfig(byte desiredCompression) throws IOException {
        // Build Configuration message:
        // [protocolVersion][CONFIGURATION_COMPRESSION_TYPE][desiredCompression]
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeByte(protocolVersion);
        dos.writeByte(CONFIGURATION_COMPRESSION_TYPE);
        dos.writeByte(desiredCompression);
        dos.flush();
        byte[] payload = baos.toByteArray();

        // Send uncompressed
        out.write(payload);
        out.flush();

        LOGGER.debug("Sent compression config with desired compression: {}", desiredCompression);

        // Read response
        byte version = in.readByte();
        LOGGER.debug("Received compression config response with protocol version: {}", version);
        byte type = in.readByte();
        if (type != CONFIGURATION_COMPRESSION_TYPE) {
            throw new IOException("Unexpected response type: " + type);
        }

        byte negotiatedCompression = in.readByte();

        if (negotiatedCompression != COMPRESSION_NONE && negotiatedCompression != COMPRESSION_ZSTD && negotiatedCompression != COMPRESSION_GZIP) {
            throw new IOException("Unsupported compression type negotiated: " + negotiatedCompression);
        }

        LOGGER.debug("Negotiated compression type: {}", negotiatedCompression);

        return negotiatedCompression;
    }

    /**
     * Sends the configuration packet to negotiate chunk size.
     */
    public int sendChunkSizeConfig(int desiredChunkSize) throws IOException {
        // Build Configuration message:
        // [protocolVersion][CONFIGURATION_CHUNK_SIZE_TYPE][desiredChunkSize]
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeByte(protocolVersion);
        dos.writeByte(CONFIGURATION_CHUNK_SIZE_TYPE);
        dos.writeInt(desiredChunkSize);
        dos.flush();
        byte[] payload = baos.toByteArray();

        // Send uncompressed
        out.write(payload);
        out.flush();

        LOGGER.debug("Sent chunk size config with desired chunk size: {}", desiredChunkSize);

        // Read response
        byte version = in.readByte();
        LOGGER.debug("Received chunk size config response with protocol version: {}", version);
        byte type = in.readByte();
        if (type != CONFIGURATION_CHUNK_SIZE_TYPE) {
            throw new IOException("Unexpected response type: " + type);
        }

        int negotiatedChunkSize = in.readInt();

        if (negotiatedChunkSize < MIN_CHUNK_SIZE || negotiatedChunkSize > MAX_CHUNK_SIZE) {
            throw new IOException("Negotiated chunk size out of bounds: " + negotiatedChunkSize);
        }

        LOGGER.debug("Negotiated chunk size: {}", negotiatedChunkSize);

        return negotiatedChunkSize;
    }

    /**
     * Sends an echo configuration message, this closes the configuration phase.
     */
    public void sendEchoConfig() throws IOException {
        // Build Configuration message:
        // [protocolVersion][CONFIGURATION_ECHO_TYPE]
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeByte(protocolVersion);
        dos.writeByte(CONFIGURATION_ECHO_TYPE);
        dos.flush();
        byte[] payload = baos.toByteArray();

        // Send uncompressed
        out.write(payload);
        out.flush();
    }

    /**
     * Closes the underlying socket and shuts down the executor.
     */
    @Override
    public void close() {
        try {
            socket.close();
        } catch (Exception e) {
            // Log or handle as needed.
        }
        executor.shutdownNow();
    }
}
