package pl.skidam.automodpack_core.protocol;

import com.github.luben.zstd.Zstd;
import pl.skidam.automodpack_core.callbacks.IntCallback;

import javax.net.ssl.*;
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

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

/**
 * A DownloadClient that creates a pool of connections.
 * Each connection performs an initial plain-text handshake by sending the AMMC magic,
 * waiting for the AMOK reply, and then upgrading the same socket to TLSv1.3.
 * Subsequent protocol messages are framed and compressed (using Zstd) to match your full protocol.
 */
public class DownloadClient implements AutoCloseable {
    private final List<Connection> connections = new ArrayList<>();

    /**
     * Creates a new {@link DownloadClient} for the specified address. If the first connection fails with a verification
     * error on a self-signed certificate, {@code trustedByUserCallback} is executed to determine whether the
     * certificate should be trusted anyway.
     *
     * @param address               the server's address
     * @param secretBytes           the secret bytes obtained from the server
     * @param poolSize              the number of connections
     * @param trustedByUserCallback the callback to determine whether a certificate should be trusted
     */
    public DownloadClient(InetSocketAddress address, byte[] secretBytes, int poolSize,
                          Function<X509Certificate, Boolean> trustedByUserCallback)
            throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException,
            KeyManagementException {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null);

        for (int i = 0; i < poolSize; i++) {
            PreValidationConnection preValidationConnection = new PreValidationConnection(address, keyStore);

            if (i == 0 && trustedByUserCallback != null
                    && preValidationConnection.getUnvalidatedCertificate() != null) {
                if (trustedByUserCallback.apply(preValidationConnection.getUnvalidatedCertificate())) {
                    keyStore.setCertificateEntry(address.getHostString(),
                            preValidationConnection.getUnvalidatedCertificate());
                    preValidationConnection = new PreValidationConnection(address, keyStore);
                }
            }

            connections.add(new Connection(preValidationConnection, secretBytes));
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
    public CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, IntCallback chunkCallback) {
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
     */
    public PreValidationConnection(InetSocketAddress address, KeyStore keyStore) throws IOException,
            NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        // Step 1. Create a plain TCP connection.
        Socket plainSocket = new Socket();
        plainSocket.connect(address, 15000);
        plainSocket.setSoTimeout(15000);
        DataOutputStream plainOut = new DataOutputStream(plainSocket.getOutputStream());
        DataInputStream plainIn = new DataInputStream(plainSocket.getInputStream());

        // Step 2. Send the handshake (AMMC magic) over the plain socket.
        plainOut.writeInt(MAGIC_AMMC);
        plainOut.flush();

        // Step 3. Wait for the server’s reply (AMOK magic).
        int handshakeResponse = plainIn.readInt();
        if (handshakeResponse != MAGIC_AMOK) {
            plainSocket.close();
            throw new IOException("Invalid handshake response from server: " + handshakeResponse);
        }

        // Step 4. Upgrade the plain socket to TLS using the same underlying connection.
        AtomicReference<X509Certificate[]> interceptedCertificateChain = new AtomicReference<>();

        SSLContext context = createSSLContext(keyStore, interceptedCertificateChain::set);
        SSLSocketFactory factory = context.getSocketFactory();
        // The createSocket(Socket, host, port, autoClose) wraps the existing plain socket.
        SSLSocket sslSocket = (SSLSocket) factory.createSocket(plainSocket, address.getHostName(), address.getPort(), true);
        sslSocket.setEnabledProtocols(new String[]{"TLSv1.3"});
        sslSocket.setEnabledCipherSuites(new String[]{"TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256"});
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        sslSocket.setSSLParameters(sslParameters);

        SSLSession session = sslSocket.getSession();
        if (!session.isValid()) {
            // Handshake failed
            sslSocket.close();
            sslSocket = null;
            X509Certificate[] serverCertificateChain = interceptedCertificateChain.get();
            if (serverCertificateChain != null && serverCertificateChain.length > 0
                    && isSelfSigned(serverCertificateChain[0])) {
                unvalidatedCertificate = serverCertificateChain[0];
            } else {
                unvalidatedCertificate = null;
            }
        } else {
            unvalidatedCertificate = null;
        }

        socket = sslSocket;
    }

    private static boolean isSelfSigned(X509Certificate certificate) {
        try {
            certificate.verify(certificate.getPublicKey());
        } catch (CertificateException | NoSuchAlgorithmException | SignatureException | InvalidKeyException |
                 NoSuchProviderException e) {
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
            throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        X509ExtendedTrustManager trustManager = createTrustManager(trustedCertificates, onValidating);
        sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());
        return sslContext;
    }

    private X509ExtendedTrustManager createTrustManager(KeyStore trustedCertificates,
                                                        Consumer<X509Certificate[]> onValidating)
            throws NoSuchAlgorithmException, KeyStoreException {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init((KeyStore) null);

        X509ExtendedTrustManager defaultTrustManager = getX509ExtendedTrustManager(trustManagerFactory);

        X509ExtendedTrustManager trustedCertificatesTrustManager = null;
        if (trustedCertificates.size() > 0) {
            trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustedCertificates);

            trustedCertificatesTrustManager = getX509ExtendedTrustManager(trustManagerFactory);
        }

        X509ExtendedTrustManager finalTrustedCertificatesTrustManager = trustedCertificatesTrustManager;
        return new X509ExtendedTrustManager() {
            private X509Certificate[] mergeCertificates() {
                ArrayList<X509Certificate> resultingCerts
                        = new ArrayList<>(Arrays.asList(defaultTrustManager.getAcceptedIssuers()));
                if (finalTrustedCertificatesTrustManager != null) {
                    resultingCerts.addAll(Arrays.asList(finalTrustedCertificatesTrustManager.getAcceptedIssuers()));
                }
                return resultingCerts.toArray(new X509Certificate[0]);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                onValidating.accept(chain);
                try {
                    defaultTrustManager.checkClientTrusted(chain, authType);
                } catch (CertificateException e) {
                    if (finalTrustedCertificatesTrustManager != null) {
                        finalTrustedCertificatesTrustManager.checkClientTrusted(chain, authType);
                    } else {
                        throw e;
                    }
                }
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                onValidating.accept(chain);
                try {
                    defaultTrustManager.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    if (finalTrustedCertificatesTrustManager != null) {
                        finalTrustedCertificatesTrustManager.checkServerTrusted(chain, authType);
                    } else {
                        throw e;
                    }
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return mergeCertificates();
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket checkedSocket)
                    throws CertificateException {
                try {
                    defaultTrustManager.checkClientTrusted(chain, authType, checkedSocket);
                } catch (CertificateException e) {
                    if (finalTrustedCertificatesTrustManager != null) {
                        // Skip address check if certificate is trusted
                        finalTrustedCertificatesTrustManager.checkClientTrusted(chain, authType);
                    } else {
                        throw e;
                    }
                }
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket checkedSocket)
                    throws CertificateException {
                onValidating.accept(chain);
                try {
                    defaultTrustManager.checkServerTrusted(chain, authType, checkedSocket);
                } catch (CertificateException e) {
                    if (finalTrustedCertificatesTrustManager != null) {
                        // Skip address check if certificate is trusted
                        finalTrustedCertificatesTrustManager.checkServerTrusted(chain, authType);
                    } else {
                        throw e;
                    }
                }
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine sslEngine)
                    throws CertificateException {
                try {
                    defaultTrustManager.checkClientTrusted(chain, authType, sslEngine);
                } catch (CertificateException e) {
                    if (finalTrustedCertificatesTrustManager != null) {
                        // Skip address check if certificate is trusted
                        finalTrustedCertificatesTrustManager.checkClientTrusted(chain, authType);
                    } else {
                        throw e;
                    }
                }
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine sslEngine)
                    throws CertificateException {
                onValidating.accept(chain);
                try {
                    defaultTrustManager.checkServerTrusted(chain, authType, sslEngine);
                } catch (CertificateException e) {
                    if (finalTrustedCertificatesTrustManager != null) {
                        // Skip address check if certificate is trusted
                        finalTrustedCertificatesTrustManager.checkServerTrusted(chain, authType);
                    } else {
                        throw e;
                    }
                }
            }
        };
    }

    private static X509ExtendedTrustManager getX509ExtendedTrustManager(TrustManagerFactory trustManagerFactory) {
        X509ExtendedTrustManager trustedCertificatesTrustManager = null;
        for (TrustManager trustManager : trustManagerFactory.getTrustManagers()) {
            if (trustManager instanceof X509ExtendedTrustManager) {
                trustedCertificatesTrustManager = (X509ExtendedTrustManager) trustManager;
                break;
            }
        }
        if (trustedCertificatesTrustManager == null) {
            throw new IllegalStateException("Failed to create extended trust manager");
        }
        return trustedCertificatesTrustManager;
    }
}

/**
 * A helper class representing a single connection.
 * Outbound messages are compressed with Zstd and framed; inbound frames are decompressed and processed.
 */
class Connection implements AutoCloseable {
    private static final byte PROTOCOL_VERSION = 1;

    private final boolean useCompression;
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
//        useCompression = !AddressHelpers.isLocal(address);
        if (preValidationConnection.getSocket() == null) {
            throw new SSLHandshakeException("Server certificate is not valid");
        }
        this.socket = preValidationConnection.getSocket();
        useCompression = true;
        this.secretBytes = secretBytes;

        try {
            // Now use the SSL socket for further communication.
            this.in = new DataInputStream(this.socket.getInputStream());
            this.out = new DataOutputStream(this.socket.getOutputStream());
            LOGGER.debug("Connection established with: {}", this.socket.getInetAddress().getHostAddress());
        } catch (IOException e) {
            throw new IOException("Failed to establish connection", e);
        }
    }

    public boolean isBusy() {
        return busy.get();
    }

    public void setBusy(boolean value) {
        busy.set(value);
    }

    /**
     * Sends a file request over this connection.
     */
    public CompletableFuture<Path> sendDownloadFile(byte[] fileHash, Path destination, IntCallback chunkCallback) {
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
                dos.writeByte(PROTOCOL_VERSION);
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
                dos.writeByte(PROTOCOL_VERSION);
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
     * Compresses (if enabled) and writes a protocol message using Zstd in chunks.
     * Each chunk is framed individually.
     * Framing without compression: [int: chunkLength][chunk payload].
     * Framing with compression: [int: compressedChunkLength][int: originalChunkLength][compressed chunk payload].
     */
    private void writeProtocolMessage(byte[] payload) throws IOException {
        int offset = 0;
        while (offset < payload.length) {
            int bytesToSend = Math.min(payload.length - offset, CHUNK_SIZE);
            byte[] chunk = new byte[bytesToSend];
            System.arraycopy(payload, offset, chunk, 0, bytesToSend);

            if (!useCompression) {
                out.writeInt(chunk.length);
                out.write(chunk);
            } else {
                byte[] compressedChunk = Zstd.compress(chunk);
                out.writeInt(compressedChunk.length);
                out.writeInt(chunk.length);
                out.write(compressedChunk);
            }
            offset += bytesToSend;
        }
        out.flush();
    }

    /**
     * Reads one framed protocol message, decompressing it.
     */
    private byte[] readProtocolMessageFrame() throws IOException {
        if (!useCompression) {
            int originalLength = in.readInt();
            byte[] data = new byte[originalLength];
            in.readFully(data);
            return data;
        } else {
            int compressedLength = in.readInt();
            int originalLength = in.readInt();

            if (compressedLength < 0 || originalLength < 0) {
                throw new IllegalArgumentException("Invalid compressed or original length");
            }

            if (originalLength > CHUNK_SIZE) {
                throw new IllegalArgumentException("Original length exceeds maximum packet size");
            }

            byte[] compressed = new byte[compressedLength];
            in.readFully(compressed);
            byte[] decompressed = Zstd.decompress(compressed, originalLength);

            if (decompressed.length != originalLength) {
                throw new IOException("Decompressed length does not match original length");
            }

            return decompressed;
        }
    }

    /**
     * Processes a file/refresh response according to your protocol.
     * The response is expected to have:
     * - A header frame: [protocolVersion][messageType][(if FILE_RESPONSE_TYPE) long expectedFileSize]
     * - One or more data frames containing file data until the total file size is reached.
     * - A final frame: [protocolVersion][END_OF_TRANSMISSION]
     */
    private Path readFileResponse(Path destination, IntCallback chunkCallback) throws IOException {
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
                    chunkCallback.run(toWrite);
                }
            }

            fos.close();

            // Read EOT frame
            byte[] eotFrame = readProtocolMessageFrame();
            try (DataInputStream eotIn = new DataInputStream(new ByteArrayInputStream(eotFrame))) {
                byte ver = eotIn.readByte();
                byte eotType = eotIn.readByte();

                if (ver != version || eotType != END_OF_TRANSMISSION) {
                    throw new IOException("Invalid end-of-transmission marker. Expected version " + version +
                            " and type " + END_OF_TRANSMISSION + ", got version " + ver + " and type " + eotType);
                }
            }

            return destination;
        }
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