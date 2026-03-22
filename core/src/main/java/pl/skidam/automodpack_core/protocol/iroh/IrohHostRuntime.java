package pl.skidam.automodpack_core.protocol.iroh;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.GAME_CALL;
import static pl.skidam.automodpack_core.Constants.privateDir;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import dev.iroh.IrohBiStream;
import dev.iroh.IrohConnection;
import dev.iroh.IrohNode;
import dev.iroh.IrohPeer;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import pl.skidam.automodpack_core.auth.PlayerEndpointsStore;
import pl.skidam.automodpack_core.protocol.HostModpackOperations;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;

public class IrohHostRuntime implements AutoCloseable {
    private static final int MAX_REQUEST_HASH_BYTES = 128;
    private static final int MAX_REFRESH_HASH_COUNT = 100_000;
    private static final int MAX_REFRESH_HASH_TOTAL_BYTES = 8 * 1024 * 1024;
    private static final int FRAME_HEADER_BYTES = Integer.BYTES * 2;

    private volatile ExecutorService acceptExecutor;
    private volatile ExecutorService workerExecutor;
    private volatile IrohNode node;
    private volatile boolean running;
    private volatile String endpointId;

    public synchronized boolean start() {
        if (running) {
            return true;
        }

        if (!IrohAvailability.warnIfUnavailable(LOGGER)) {
            return false;
        }

        try {
            Files.createDirectories(privateDir);
            IrohNode created = AutoModpackIrohNodes.createTunnelNode();
            created.setHookDefaultAccept(false);
            created.setConnectionHook((phase, remoteId, alpn, isIncoming) -> {
                if (phase == dev.iroh.ConnectionHook.HookPhase.BEFORE_CONNECT || !isIncoming) {
                    return true;
                }
                // TODO extend the JNI hook with remote address/path metadata so pure iroh
                // connections can enforce IP bans in addition to UUID-based auth.
                return isEndpointAuthorized(remoteId);
            });

            this.node = created;
            this.endpointId = IrohIdentity.toHex(created.getId());
            this.acceptExecutor = Executors.newSingleThreadExecutor(IrohThreading.daemonThreadFactory("AutoModpack Iroh Accept"));
            this.workerExecutor = Executors.newCachedThreadPool(IrohThreading.daemonThreadFactory("AutoModpack Iroh Worker"));
            this.running = true;
            LOGGER.info("Started iroh runtime with endpoint {}", endpointId);
            acceptExecutor.execute(this::acceptLoop);
            return true;
        } catch (Throwable e) {
            LOGGER.error("Failed to start iroh runtime", e);
            close();
            return false;
        }
    }

    public synchronized IrohPeer bootstrapPeer(byte[] peerId) {
        if (!running || node == null) {
            return null;
        }
        return node.addPeer(peerId);
    }

    public boolean isRunning() {
        return running && node != null;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public java.util.List<InetSocketAddress> getDirectAddresses() {
        IrohNode activeNode = this.node;
        if (activeNode == null) {
            return java.util.List.of();
        }
        return activeNode.getDirectIpAddresses();
    }

    public boolean isEndpointAuthorized(byte[] remoteId) {
        if (remoteId == null || remoteId.length == 0) {
            return false;
        }
        String endpointIdHex = IrohIdentity.toHex(remoteId);
        String playerUuid = PlayerEndpointsStore.getPlayerUuidForEndpoint(endpointIdHex);
        return playerUuid != null && GAME_CALL.isPlayerAuthorized(playerUuid);
    }

    private void acceptLoop() {
        while (running) {
            try {
                IrohNode activeNode = this.node;
                if (activeNode == null) {
                    return;
                }

                IrohConnection conn = activeNode.accept(1_000);
                if (conn == null) {
                    continue;
                }
                LOGGER.info("Accepted iroh connection from {}", IrohTransportSupport.shortPeerId(conn.getRemoteId()));
                LOGGER.debug("Accepted iroh connection paths: {}", IrohPathSummary.describe(conn));
                workerExecutor.execute(() -> handleConnection(conn));
            } catch (IllegalStateException ignored) {
                return;
            } catch (Exception e) {
                if (running) {
                    LOGGER.error("Iroh accept loop failed", e);
                }
            }
        }
    }

    private void handleConnection(IrohConnection conn) {
        try (conn) {
            while (running) {
                IrohConnection.StreamOpenResult result = conn.acceptBiResult(1_000);
                if (result.isTimeout()) {
                    continue;
                }
                if (!result.isOpened()) {
                    return;
                }
                IrohBiStream stream = result.getStream();
                workerExecutor.execute(() -> handleStream(stream));
            }
        } catch (Exception e) {
            if (running) {
                LOGGER.debug("Iroh connection handler stopped", e);
            }
        }
    }

    private void handleStream(IrohBiStream stream) {
        try (stream;
             DataInputStream requestIn = new DataInputStream(new BufferedInputStream(IrohStreamAdapters.input(stream, IrohTransportSupport.STREAM_READ_TIMEOUT_MS)));
             OutputStream output = IrohStreamAdapters.output(stream)) {
            int versionByte = requestIn.read();
            if (versionByte < 0) {
                stream.finish();
                return;
            }

            byte responseVersion = LATEST_SUPPORTED_PROTOCOL_VERSION;
            byte responseCompressionType = COMPRESSION_GZIP;
            int responseChunkSize = DEFAULT_CHUNK_SIZE;

            try {
                byte version = (byte) versionByte;
                byte type = requestIn.readByte();
                responseCompressionType = normalizeCompressionType(requestIn.readByte());
                responseChunkSize = clampChunkSize(requestIn.readInt());
                responseVersion = version == LATEST_SUPPORTED_PROTOCOL_VERSION ? version : LATEST_SUPPORTED_PROTOCOL_VERSION;

                if (version != LATEST_SUPPORTED_PROTOCOL_VERSION) {
                    throw new IOException("Unsupported protocol version: " + version);
                }

                switch (type) {
                    case FILE_REQUEST_TYPE -> {
                        byte[] hashBytes = readBoundedBytes(requestIn, "file hash", MAX_REQUEST_HASH_BYTES);
                        sendFile(output, responseVersion, responseCompressionType, responseChunkSize, hashBytes);
                    }
                    case REFRESH_REQUEST_TYPE -> {
                        byte[][] hashes = readRefreshHashes(requestIn);
                        HostModpackOperations.refreshModpackFiles(hashes);
                        sendFile(output, responseVersion, responseCompressionType, responseChunkSize, new byte[0]);
                    }
                    default -> throw new IOException("Unknown message type");
                }
                ensureRequestFullyConsumed(requestIn);
            } catch (IOException e) {
                try {
                    sendError(output, responseVersion, responseCompressionType, responseChunkSize, protocolErrorMessage(e));
                } catch (IOException sendErrorFailure) {
                    LOGGER.debug("Failed to send iroh protocol error", sendErrorFailure);
                }
            }
            stream.finish();
        } catch (Exception e) {
            LOGGER.debug("Iroh stream handler failed", e);
        }
    }

    private void sendFile(OutputStream output, byte version, byte compressionType, int chunkSize, byte[] hashBytes) throws IOException {
        String sha1 = new String(hashBytes, StandardCharsets.UTF_8);
        Optional<Path> optionalPath = HostModpackOperations.resolvePath(sha1);
        if (optionalPath.isEmpty() || !Files.exists(optionalPath.get())) {
            sendError(output, version, compressionType, chunkSize, "File not found");
            return;
        }

        CompressionCodec codec = resolveCodec(compressionType);

        Path path = optionalPath.get();
        long fileSize = Files.size(path);

        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        try (DataOutputStream header = new DataOutputStream(headerBytes)) {
            header.writeByte(version);
            header.writeByte(FILE_RESPONSE_TYPE);
            header.writeLong(fileSize);
        }
        writeFrame(output, codec, chunkSize, headerBytes.toByteArray());

        if (fileSize == 0) {
            writeFrame(output, codec, chunkSize, new byte[] { version, END_OF_TRANSMISSION });
            return;
        }

        try (InputStream fileIn = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] chunk = new byte[chunkSize];
            int read;
            while ((read = fileIn.read(chunk)) != -1) {
                writeFrame(output, codec, chunkSize, chunk, read);
            }
        }

        writeFrame(output, codec, chunkSize, new byte[] { version, END_OF_TRANSMISSION });
    }

    private void sendError(OutputStream output, byte version, byte compressionType, int chunkSize, String message) throws IOException {
        CompressionCodec codec = resolveCodec(compressionType);

        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeByte(version);
            data.writeByte(ERROR);
            data.writeInt(messageBytes.length);
            data.write(messageBytes);
        }
        writeFrame(output, codec, chunkSize, bytes.toByteArray());
    }

    private void writeFrame(OutputStream output, CompressionCodec codec, int chunkSize, byte[] payload) throws IOException {
        writeFrame(output, codec, chunkSize, payload, payload.length);
    }

    private void writeFrame(OutputStream output, CompressionCodec codec, int chunkSize, byte[] payload, int payloadLength) throws IOException {
        if (payloadLength < 0 || payloadLength > payload.length) {
            throw new IOException("Invalid iroh payload length: " + payloadLength);
        }

        int offset = 0;
        byte[] frameHeader = new byte[FRAME_HEADER_BYTES];
        while (offset < payloadLength) {
            int bytesToSend = Math.min(payloadLength - offset, chunkSize);
            byte[] compressedChunk = codec.compress(payload, offset, bytesToSend);
            writeFrameHeader(output, frameHeader, compressedChunk.length, bytesToSend);
            output.write(compressedChunk);
            offset += bytesToSend;
        }
        if (payloadLength == 0) {
            byte[] compressedChunk = codec.compress(payload, 0, 0);
            writeFrameHeader(output, frameHeader, compressedChunk.length, 0);
            output.write(compressedChunk);
        }
        output.flush();
    }

    private static byte[] readBoundedBytes(DataInputStream in, String fieldName, int maxLength) throws IOException {
        int length = readBoundedLength(in, fieldName, 0, maxLength);
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    private static byte[][] readRefreshHashes(DataInputStream in) throws IOException {
        int hashCount = readBoundedLength(in, "refresh hash count", 0, MAX_REFRESH_HASH_COUNT);
        if (hashCount == 0) {
            return new byte[0][];
        }

        int hashLength = readBoundedLength(in, "refresh hash length", 0, MAX_REQUEST_HASH_BYTES);
        long totalHashBytes = (long) hashCount * hashLength;
        if (totalHashBytes > MAX_REFRESH_HASH_TOTAL_BYTES) {
            throw new IOException("Refresh request exceeds " + MAX_REFRESH_HASH_TOTAL_BYTES + " bytes");
        }

        byte[][] hashes = new byte[hashCount][];
        for (int i = 0; i < hashCount; i++) {
            hashes[i] = new byte[hashLength];
            in.readFully(hashes[i]);
        }
        return hashes;
    }

    private static int readBoundedLength(DataInputStream in, String fieldName, int min, int max) throws IOException {
        int length = in.readInt();
        if (length < min) {
            throw new IOException("Negative " + fieldName);
        }
        if (length > max) {
            throw new IOException(fieldName + " exceeds " + max + " bytes");
        }
        return length;
    }

    private static void ensureRequestFullyConsumed(DataInputStream in) throws IOException {
        if (in.read() != -1) {
            throw new IOException("Unexpected trailing request bytes");
        }
    }

    private static CompressionCodec resolveCodec(byte compressionType) {
        try {
            CompressionCodec codec = CompressionFactory.getCodec(compressionType);
            if (codec.isInitialized()) {
                return codec;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return CompressionFactory.getCodec(COMPRESSION_GZIP);
    }

    private static byte normalizeCompressionType(byte compressionType) {
        return resolveCodec(compressionType).getCompressionType();
    }

    private static String protocolErrorMessage(IOException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "Invalid iroh request" : message;
    }

    private static void writeFrameHeader(OutputStream output, byte[] header, int compressedLength, int originalLength) throws IOException {
        putInt(header, 0, compressedLength);
        putInt(header, Integer.BYTES, originalLength);
        output.write(header);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static int clampChunkSize(int chunkSize) {
        return Math.max(MIN_CHUNK_SIZE, Math.min(MAX_CHUNK_SIZE, chunkSize));
    }

    @Override
    public synchronized void close() {
        running = false;
        IrohNode activeNode = node;
        node = null;
        endpointId = null;
        ExecutorService acceptPool = acceptExecutor;
        ExecutorService workerPool = workerExecutor;
        acceptExecutor = null;
        workerExecutor = null;
        if (acceptPool != null) {
            acceptPool.shutdownNow();
        }
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
        awaitTermination(acceptPool, "iroh accept");
        awaitTermination(workerPool, "iroh worker");
        if (activeNode != null) {
            activeNode.close();
        }
    }

    private void awaitTermination(ExecutorService executor, String name) {
        if (executor == null) {
            return;
        }
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                LOGGER.debug("Timed out waiting for {} executor shutdown", name);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("Interrupted while waiting for {} executor shutdown", name);
        }
    }
}
