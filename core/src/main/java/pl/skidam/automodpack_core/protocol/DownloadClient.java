package pl.skidam.automodpack_core.protocol;

import pl.skidam.automodpack_core.auth.Secrets;
import com.github.luben.zstd.Zstd;
import pl.skidam.automodpack_core.callbacks.IntCallback;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

/**
 * A DownloadClient that creates a pool of connections.
 * Each connection performs an initial plain-text handshake by sending the AMMC magic,
 * waiting for the AMOK reply, and then upgrading the same socket to TLSv1.3.
 * Subsequent protocol messages are framed and compressed (using Zstd) to match your full protocol.
 */
public class DownloadClient {
    private final List<Connection> connections = new ArrayList<>();

    public DownloadClient(InetSocketAddress address, Secrets.Secret secret, int poolSize) throws Exception {
        for (int i = 0; i < poolSize; i++) {
            connections.add(new Connection(address, secret));
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
    public void close() {
        for (Connection conn : connections) {
            conn.close();
        }

        connections.clear();
    }
}

/**
 * A helper class representing a single connection.
 * It first performs a plain-text handshake then upgrades the same socket to TLS.
 * Outbound messages are compressed with Zstd and framed; inbound frames are decompressed and processed.
 */
class Connection {
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
     * Creates a new connection by first opening a plain TCP socket,
     * sending the AMMC magic, waiting for the AMOK reply, and then upgrading to TLS.
     */
    public Connection(InetSocketAddress address, Secrets.Secret secret) throws Exception {
        try {
            if (address == null || !knownHosts.hosts.containsKey(address.getHostString())) {
                throw new IllegalArgumentException("Invalid address or unknown host: " + address);
            }

            // Step 1. Create a plain TCP connection.
            LOGGER.debug("Initializing connection to: {}", address.getHostString());
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
            SSLContext context = createSSLContext();
            SSLSocketFactory factory = context.getSocketFactory();
            // The createSocket(Socket, host, port, autoClose) wraps the existing plain socket.
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(plainSocket, address.getHostName(), address.getPort(), true);
            sslSocket.setEnabledProtocols(new String[]{"TLSv1.3"});
            sslSocket.setEnabledCipherSuites(new String[]{"TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"});
            sslSocket.startHandshake();

            // Step 5. Perform custom TLS certificate validation.
            Certificate[] certs = sslSocket.getSession().getPeerCertificates();
            if (certs == null || certs.length == 0 || certs.length > 3) {
                sslSocket.close();
                throw new IOException("Invalid server certificate chain");
            }

            String certificateFingerprint = knownHosts.hosts.get(address.getHostString());
            boolean validated = false;
            for (Certificate cert : certs) {
                if (cert instanceof X509Certificate x509Cert) {
                    String fingerprint = NetUtils.getFingerprint(x509Cert);
                    if (fingerprint.equals(certificateFingerprint)) {
                        validated = true;
                        break;
                    }
                }
            }

            if (!validated) {
                sslSocket.close();
                throw new IOException("Server certificate validation failed");
            }

//        useCompression = !AddressHelpers.isLocal(address);
            useCompression = true;
            secretBytes = Base64.getUrlDecoder().decode(secret.secret());

            // Now use the SSL socket for further communication.
            this.socket = sslSocket;
            this.in = new DataInputStream(sslSocket.getInputStream());
            this.out = new DataOutputStream(sslSocket.getOutputStream());
            LOGGER.debug("Connection established with: {}", address.getHostString());
        } catch (Exception e) {
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
     *   - A header frame: [protocolVersion][messageType][(if FILE_RESPONSE_TYPE) long expectedFileSize]
     *   - One or more data frames containing file data until the total file size is reached.
     *   - A final frame: [protocolVersion][END_OF_TRANSMISSION]
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
            OutputStream fos = new FileOutputStream(destination.toFile()) ;

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
                int toWrite = Math.min(dataFrame.length, (int)(expectedFileSize - receivedBytes));

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
    public void close() {
        try {
            socket.close();
        } catch (Exception e) {
            // Log or handle as needed.
        }
        executor.shutdownNow();
    }

    /**
     * Creates an SSLContext that trusts all certificates (like InsecureTrustManagerFactory).
     */
    private SSLContext createSSLContext() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                public void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }
        };
        sslContext.init(null, trustAllCerts, new SecureRandom());
        return sslContext;
    }
}