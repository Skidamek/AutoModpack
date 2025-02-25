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

import static pl.skidam.automodpack_core.protocol.NetUtils.*;

/**
 * A DownloadClient that creates a pool of connections.
 * Each connection performs an initial plain-text handshake by sending the AMMC magic,
 * waiting for the AMOK reply, and then upgrading the same socket to TLSv1.3.
 * Subsequent protocol messages are framed and compressed (using Zstd) to match your full protocol.
 */
public class DownloadClient {
    private final List<Connection> connections = new ArrayList<>();

    public DownloadClient(InetSocketAddress remoteAddress, Secrets.Secret secret, int poolSize) throws Exception {
        for (int i = 0; i < poolSize; i++) {
            connections.add(new Connection(remoteAddress, secret));
        }
    }

    private synchronized Connection getFreeConnection() {
        for (Connection conn : connections) {
            if (!conn.isBusy()) {
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
    public CompletableFuture<Object> downloadFile(byte[] fileHash, Path destination, IntCallback chunkCallback) {
        Connection conn = getFreeConnection();
        return conn.sendDownloadFile(fileHash, destination, chunkCallback);
    }

    /**
     * Sends a refresh request with the given file hashes.
     */
    public CompletableFuture<Object> requestRefresh(byte[][] fileHashes) {
        Connection conn = getFreeConnection();
        return conn.sendRefreshRequest(fileHashes);
    }

    /**
     * Closes all connections.
     */
    public void close() {
        for (Connection conn : connections) {
            conn.close();
        }
    }
}

/**
 * A helper class representing a single connection.
 * It first performs a plain-text handshake then upgrades the same socket to TLS.
 * Outbound messages are compressed with Zstd and framed; inbound frames are decompressed and processed.
 */
class Connection {
    private static final byte PROTOCOL_VERSION = 1;

    private final byte[] secretBytes;
    private final SSLSocket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /**
     * Creates a new connection by first opening a plain TCP socket,
     * sending the AMMC magic, waiting for the AMOK reply, and then upgrading to TLS.
     */
    public Connection(InetSocketAddress remoteAddress, Secrets.Secret secret) throws Exception {
        // Step 1. Create a plain TCP connection.
        Socket plainSocket = new Socket(remoteAddress.getHostName(), remoteAddress.getPort());
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
        SSLSocket sslSocket = (SSLSocket) factory.createSocket(plainSocket, remoteAddress.getHostName(), remoteAddress.getPort(), true);
        sslSocket.setEnabledProtocols(new String[] {"TLSv1.3"});
        sslSocket.setEnabledCipherSuites(new String[] {"TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"});
        sslSocket.startHandshake();

        // Step 5. Perform custom TLS certificate validation.
        Certificate[] certs = sslSocket.getSession().getPeerCertificates();
        if (certs == null || certs.length == 0 || certs.length > 3) {
            sslSocket.close();
            throw new IOException("Invalid server certificate chain");
        }

        boolean validated = false;
        for (Certificate cert : certs) {
            if (cert instanceof X509Certificate x509Cert) {
                String fingerprint = NetUtils.getFingerprint(x509Cert, secret.secret());
                if (fingerprint.equals(secret.fingerprint())) {
                    validated = true;
                    break;
                }
            }
        }

        if (!validated) {
            sslSocket.close();
            throw new IOException("Server certificate validation failed");
        }

        secretBytes = Base64.getUrlDecoder().decode(secret.secret());

        // Now use the SSL socket for further communication.
        this.socket = sslSocket;
        this.in = new DataInputStream(sslSocket.getInputStream());
        this.out = new DataOutputStream(sslSocket.getOutputStream());
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
    public CompletableFuture<Object> sendDownloadFile(byte[] fileHash, Path destination, IntCallback chunkCallback) {
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
    public CompletableFuture<Object> sendRefreshRequest(byte[][] fileHashes) {
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
                return readFileResponse(null, null);
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
     * Compresses and writes a protocol message using Zstd.
     * Message framing: [int: compressedLength][int: originalLength][compressed payload].
     */
    private void writeProtocolMessage(byte[] payload) throws IOException {
        byte[] compressed = Zstd.compress(payload);
        out.writeInt(compressed.length);
        out.writeInt(payload.length);
        out.write(compressed);
        out.flush();
    }

    /**
     * Reads one framed protocol message, decompressing it.
     */
    private byte[] readProtocolMessageFrame() throws IOException {
        int compLength = in.readInt();
        int origLength = in.readInt();
        byte[] compData = new byte[compLength];
        in.readFully(compData);
        return Zstd.decompress(compData, origLength);
    }

    /**
     * Processes a file/refresh response according to your protocol.
     * The response is expected to have:
     *   - A header frame: [protocolVersion][messageType][(if FILE_RESPONSE_TYPE) long expectedFileSize]
     *   - One or more data frames containing file data until the total file size is reached.
     *   - A final frame: [protocolVersion][END_OF_TRANSMISSION]
     */
    private Object readFileResponse(Path destination, IntCallback chunkCallback) throws IOException {
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
            OutputStream fos = (destination != null) ? new FileOutputStream(destination.toFile()) : null;
            List<byte[]> rawData = (fos == null) ? new LinkedList<>() : null;

            if (messageType == END_OF_TRANSMISSION) {
                if (fos != null) fos.close();
                return (rawData != null) ? rawData : destination;
            }

            if (messageType != FILE_RESPONSE_TYPE) {
                if (fos != null) fos.close();
                throw new IOException("Unexpected message type: " + messageType);
            }

            long expectedFileSize = headerIn.readLong();

            // Read data frames until the expected file size is received.
            while (receivedBytes < expectedFileSize) {
                byte[] dataFrame = readProtocolMessageFrame();
                int toWrite = Math.min(dataFrame.length, (int)(expectedFileSize - receivedBytes));

                if (fos != null) {
                    fos.write(dataFrame, 0, toWrite);
                } else {
                    byte[] chunk = Arrays.copyOfRange(dataFrame, 0, toWrite);
                    rawData.add(chunk);
                }
                receivedBytes += toWrite;

                if (chunkCallback != null) {
                    chunkCallback.run(toWrite);
                }
            }

            if (fos != null) fos.close();

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

            return (rawData != null) ? rawData : destination;
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
