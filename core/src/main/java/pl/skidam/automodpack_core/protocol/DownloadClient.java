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
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import javax.net.ssl.*;
import pl.skidam.automodpack_core.auth.DnsPinResolver;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.utils.PlatformUtils;

public class DownloadClient implements AutoCloseable {

    // Dedicated daemon pool for the blocking network/login stages (pool
    // hydration, and the up-to-120s certificate prompt). Keeps long downloads
    // and that prompt off ForkJoinPool.commonPool, which is shared JVM-wide and
    // also drives the parallel streams used here and in ModpackContent.
    public static final ExecutorService NET_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "automodpack-net");
        t.setDaemon(true);
        return t;
    });

    private final List<Connection> connections = new ArrayList<>();

    private record InitialConnectionResult(PreValidationConnection connection, SSLContext sslContext) {}

    /**
     * Holds the outcome of a single probe attempt, along with the KeyStore for later mutation.
     */
    private record ProbeResult(InitialConnectionResult success, X509Certificate untrustedCert, IOException error, KeyStore keyStore) {}

    private DownloadClient(List<Connection> connections) {
        this.connections.addAll(connections);
    }

    /**
     * Async factory. If the certificate needs user approval, no thread blocks: the returned future
     * completes when the trust callback's future completes (via UI callbacks on the render thread).
     */
    public static CompletableFuture<DownloadClient> createAsync(
            Jsons.ModpackAddresses addresses,
            byte[] secretBytes,
            int poolSize,
            Function<X509Certificate, CompletableFuture<Boolean>> trustCallback) {

        if (poolSize < 1) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Pool size must be greater than 0"));
        }

        return CompletableFuture.supplyAsync(() -> probeConnection(addresses), NET_EXECUTOR)
                .thenCompose(probe -> {
                    if (probe.success != null) {
                        return finishConnection(probe.success, secretBytes, poolSize, addresses);
                    }
                    if (probe.untrustedCert == null) {
                        return CompletableFuture.failedFuture(probe.error);
                    }
                    if (!isSelfSigned(probe.untrustedCert)) {
                        return requestManualTrust(probe, addresses, secretBytes, poolSize, trustCallback);
                    }

                    return DnsPinResolver.resolvePinAsync(addresses.serverAddress.getHostString())
                            .thenCompose(result -> {
                                if (result instanceof DnsPinResolver.Authoritative authoritative) {
                                    try {
                                        if (!authoritative.fingerprint().equals(getFingerprint(probe.untrustedCert))) {
                                            return CompletableFuture.failedFuture(new IOException(
                                                    "Certificate does not match the DNSSEC fingerprint for "
                                                            + addresses.serverAddress.getHostString()));
                                        }
                                    } catch (CertificateEncodingException e) {
                                        return CompletableFuture.failedFuture(new IOException(
                                                "Failed to fingerprint the server certificate", e));
                                    }

                                    LOGGER.info("Trusting the self-signed certificate from {} because it matches the DNSSEC fingerprint for {}",
                                            addresses.hostAddress.getHostString(), addresses.serverAddress.getHostString());
                                    return retryWithTrustedCertificate(probe, addresses, secretBytes, poolSize);
                                }
                                if (result instanceof DnsPinResolver.Misconfigured misconfigured) {
                                    return CompletableFuture.failedFuture(new IOException(
                                            "Invalid DNSSEC AutoModpack fingerprint for "
                                                    + addresses.serverAddress.getHostString() + ": " + misconfigured.reason()));
                                }
                                return requestManualTrust(probe, addresses, secretBytes, poolSize, trustCallback);
                            });
                });
    }

    private static ProbeResult probeConnection(Jsons.ModpackAddresses addresses) {
        KeyStore keyStore = loadDefaultKeyStore();
        AtomicReference<X509Certificate[]> capturedChain = new AtomicReference<>();
        SSLContext context = createSSLContext(keyStore, capturedChain::set);

        try {
            PreValidationConnection probe = getPreValidationConnection(addresses, context);
            return new ProbeResult(new InitialConnectionResult(probe, context), null, null, keyStore);
        } catch (IOException e) {
            X509Certificate[] chain = capturedChain.get();
            X509Certificate untrusted = (chain != null && chain.length > 0) ? chain[0] : null;
            return new ProbeResult(null, untrusted, e, keyStore);
        }
    }

    private static CompletableFuture<DownloadClient> finishConnection(
            InitialConnectionResult connection,
            byte[] secretBytes,
            int poolSize,
            Jsons.ModpackAddresses addresses) {
        try {
            return CompletableFuture.completedFuture(
                    new DownloadClient(hydratePool(connection, secretBytes, poolSize, addresses)));
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static CompletableFuture<DownloadClient> requestManualTrust(
            ProbeResult probe,
            Jsons.ModpackAddresses addresses,
            byte[] secretBytes,
            int poolSize,
            Function<X509Certificate, CompletableFuture<Boolean>> trustCallback) {
        if (trustCallback == null) {
            return CompletableFuture.failedFuture(probe.error);
        }

        return trustCallback.apply(probe.untrustedCert)
                .thenComposeAsync(trusted -> {
                    if (!trusted) {
                        return CompletableFuture.failedFuture(new IOException("User rejected certificate"));
                    }
                    return retryWithTrustedCertificate(probe, addresses, secretBytes, poolSize);
                }, NET_EXECUTOR)
                .orTimeout(120, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    throw new CompletionException(new IOException("Certificate not trusted", e));
                });
    }

    private static CompletableFuture<DownloadClient> retryWithTrustedCertificate(
            ProbeResult probe,
            Jsons.ModpackAddresses addresses,
            byte[] secretBytes,
            int poolSize) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                probe.keyStore.setCertificateEntry(addresses.hostAddress.getHostString(), probe.untrustedCert);
                SSLContext trustedContext = createSSLContext(probe.keyStore, null);
                PreValidationConnection retry = getPreValidationConnection(addresses, trustedContext);
                return new DownloadClient(hydratePool(
                        new InitialConnectionResult(retry, trustedContext),
                        secretBytes, poolSize, addresses));
            } catch (Exception e) {
                throw new CompletionException(new IOException("Failed to reconnect after trust", e));
            }
        }, NET_EXECUTOR);
    }

    static boolean isSelfSigned(X509Certificate certificate) {
        if (certificate == null
                || !certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
            return false;
        }

        try {
            certificate.verify(certificate.getPublicKey());
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * Hydrates the connection pool from a successful probe and SSL context.
     */
    private static List<Connection> hydratePool(InitialConnectionResult probe, byte[] secretBytes, int poolSize, Jsons.ModpackAddresses addresses) throws IOException {
        List<Connection> conns = new ArrayList<>();
        if (probe.connection().getSocket() != null && !probe.connection().getSocket().isClosed()) {
            if (secretBytes == null) {
                probe.connection().getSocket().close();
                return conns;
            } else {
                conns.add(new Connection(probe.connection(), secretBytes));
            }
        }
        if (secretBytes == null) return conns;

        int remainingNeeded = poolSize - conns.size();
        if (remainingNeeded < 1) return conns;

        // Open the remaining connections in parallel. Record each one the moment
        // it is created so that if any sibling task fails, we can close every
        // connection that did open (including the probe) instead of leaking its
        // socket and single-thread executor.
        List<Connection> opened = Collections.synchronizedList(new ArrayList<>());
        try {
            IntStream.range(0, remainingNeeded)
                    .parallel()
                    .forEach(i -> {
                        try {
                            opened.add(new Connection(getPreValidationConnection(addresses, probe.sslContext()), secretBytes));
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    });
        } catch (RuntimeException e) {
            for (Connection c : conns) closeQuietly(c);
            for (Connection c : opened) closeQuietly(c);
            Throwable cause = (e instanceof CompletionException && e.getCause() != null) ? e.getCause() : e;
            if (cause instanceof IOException io) throw io;
            throw new IOException("Failed to hydrate connection pool", cause);
        }
        conns.addAll(opened);
        return conns;
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    private static KeyStore loadDefaultKeyStore() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null);
            return keyStore;
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
            throw new RuntimeException("Failed to initialize KeyStore", e);
        }
    }

    private static PreValidationConnection getPreValidationConnection(Jsons.ModpackAddresses modpackAddresses, SSLContext sharedContext) throws IOException {
        String hostName = modpackAddresses.hostAddress.getHostString();
        InetSocketAddress address = new InetSocketAddress(hostName, modpackAddresses.hostAddress.getPort());
        if (address.isUnresolved()) {
            throw new IOException("Failed to resolve host address: " + hostName);
        }
        return new PreValidationConnection(address, modpackAddresses, sharedContext);
    }

    private static SSLContext createSSLContext(KeyStore trustedCertificates, Consumer<X509Certificate[]> onValidating) {
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
            Function<X509Certificate, CompletableFuture<Boolean>> asyncCallback = trustedByUserCallback == null
                    ? null
                    : certificate -> CompletableFuture.completedFuture(trustedByUserCallback.apply(certificate));
            return createAsync(modpackAddresses, secretBytes, poolSize, asyncCallback).get();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            LOGGER.error("Failed to create download client: {}", cause.getMessage());
            LOGGER.debug(cause);
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

class PreValidationConnection {

    private final SSLSocket socket;

    public PreValidationConnection(InetSocketAddress resolvedHostAddress, Jsons.ModpackAddresses modpackAddresses, SSLContext sslContext) throws IOException {
        Socket plainSocket = new Socket();
        plainSocket.connect(resolvedHostAddress, 10000);
        plainSocket.setSoTimeout(10000);

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

        SSLSocketFactory factory = sslContext.getSocketFactory();
        String originHost = modpackAddresses.serverAddress.getHostString();
        SSLSocket sslSocket = (SSLSocket) factory.createSocket(plainSocket, originHost, resolvedHostAddress.getPort(), true);

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

    private final byte[] networkInputBuffer = new byte[MAX_CHUNK_SIZE + 8192];

    public Connection(PreValidationConnection preValidationConnection, byte[] secretBytes) throws IOException {
        if (preValidationConnection.getSocket() == null || preValidationConnection.getSocket().isClosed()) {
            throw new SSLHandshakeException("Server certificate invalid, connection closed");
        }
        this.socket = preValidationConnection.getSocket();
        this.secretBytes = secretBytes;

        this.in = new DataInputStream(new BufferedInputStream(this.socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(this.socket.getOutputStream()));

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

    private byte[] readProtocolMessageFrame() throws IOException {
        int compressedLength = in.readInt();
        int originalLength = in.readInt();

        int maxAllowedSize = this.chunkSize + 8192;

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
