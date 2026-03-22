package pl.skidam.automodpack_core.protocol;

import dev.iroh.IrohBiStream;
import dev.iroh.IrohConnection;
import dev.iroh.IrohNode;
import dev.iroh.IrohPathInfo;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.skidam.automodpack_core.networking.connection.AutoModpackConnectionTransport;
import pl.skidam.automodpack_core.networking.connection.AutoModpackFrameHandler;
import pl.skidam.automodpack_core.auth.PlayerEndpointsStore;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.GameCallService;
import pl.skidam.automodpack_core.loader.NullGameCall;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.iroh.IrohIdentity;
import pl.skidam.automodpack_core.protocol.iroh.IrohStreamAdapters;
import pl.skidam.automodpack_core.protocol.iroh.IrohTransportSupport;
import pl.skidam.automodpack_core.protocol.iroh.tunnel.ClientConnectionIrohTunnelSession;
import pl.skidam.automodpack_core.protocol.iroh.tunnel.ServerConnectionIrohTunnelSession;
import pl.skidam.automodpack_core.protocol.netty.handler.ProtocolServerHandler;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.ObservableMap;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.Constants.GAME_CALL;
import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.hostServer;
import static pl.skidam.automodpack_core.Constants.playerEndpointsFile;
import static pl.skidam.automodpack_core.Constants.privateDir;
import static pl.skidam.automodpack_core.protocol.NetUtils.COMPRESSION_GZIP;
import static pl.skidam.automodpack_core.protocol.NetUtils.DEFAULT_CHUNK_SIZE;
import static pl.skidam.automodpack_core.protocol.NetUtils.END_OF_TRANSMISSION;
import static pl.skidam.automodpack_core.protocol.NetUtils.FILE_REQUEST_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.FILE_RESPONSE_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.LATEST_SUPPORTED_PROTOCOL_VERSION;
import static pl.skidam.automodpack_core.Constants.serverConfig;

class AutoModpackMultiPathIntegrationTest {
    private static final Path IROH_KEY_FILE = privateDir.resolve("iroh.key");

    private ModpackHostService previousHostServer;
    private Jsons.ServerConfigFieldsV2 previousServerConfig;
    private GameCallService previousGameCall;
    private byte[] originalIrohKey;

    @BeforeEach
    void setUp() throws IOException {
        previousHostServer = hostServer;
        previousServerConfig = serverConfig;
        previousGameCall = GAME_CALL;
        originalIrohKey = Files.exists(IROH_KEY_FILE) ? Files.readAllBytes(IROH_KEY_FILE) : null;

        Files.deleteIfExists(playerEndpointsFile);
        PlayerEndpointsStore.clearForTests();

        serverConfig = new Jsons.ServerConfigFieldsV2();
        serverConfig.modpackHost = true;
        serverConfig.bindPort = 24454;
        serverConfig.portToSend = 24454;
        GAME_CALL = new NullGameCall();
        hostServer = new HybridHostServer();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (hostServer != null) {
            hostServer.stop();
        }
        restoreIrohKey();
        try {
            Files.deleteIfExists(playerEndpointsFile);
        } catch (IOException e) {
            fail("Failed to clean up player endpoints store: " + e.getMessage());
        }
        PlayerEndpointsStore.clearForTests();

        hostServer = previousHostServer;
        serverConfig = previousServerConfig;
        GAME_CALL = previousGameCall;
    }

    @Test
    void simultaneousRawAndMinecraftCarriersRemainActiveWhileDirectAndMinecraftPathsAreVisible() throws Exception {
        String hash = "multi-path-hash";
        Path source = Files.createTempFile("iroh-multipath-source-", ".txt");
        Files.writeString(source, "multi path", StandardCharsets.UTF_8);
        ObservableMap<String, Path> paths = new ObservableMap<>();
        paths.put(hash, source);
        hostServer.setPaths(paths);
        assertTrue(hostServer.start(), "Hybrid host server should start");
        String serverEndpointId = hostServer.getIrohEndpointId();
        assertNotNull(serverEndpointId);

        List<java.net.InetSocketAddress> directAddresses = waitForDirectAddresses();
        assertFalse(directAddresses.isEmpty(), "Host should expose direct iroh IP addresses");

        byte[] clientSecret = secretDifferentFrom(serverEndpointId);
        overwriteSharedKeyForNextClient(clientSecret);
        byte[] clientEndpointId = IrohIdentity.deriveEndpointId(clientSecret);
        String clientEndpointIdHex = IrohIdentity.toHex(clientEndpointId);
        String allowedUuid = UUID.randomUUID().toString();
        PlayerEndpointsStore.bindPlayer(allowedUuid, "Player", clientEndpointId);
        GAME_CALL = allowOnly(allowedUuid);

        NettyBootstrapServer bootstrapServer = NettyBootstrapServer.start();
        InMemoryTransportPair transportPair = new InMemoryTransportPair();
        ServerConnectionIrohTunnelSession serverSession = null;
        ClientConnectionIrohTunnelSession clientSession = null;
        IrohConnection connection = null;
        try {
            long sessionId = 123456789L;
            serverSession = new ServerConnectionIrohTunnelSession(
                sessionId,
                (HybridHostServer) hostServer,
                serverEndpointId,
                transportPair.server()
            );
            clientSession = new ClientConnectionIrohTunnelSession(
                sessionId,
                serverEndpointId,
                transportPair.client(),
                AddressHelpers.format("127.0.0.1", bootstrapServer.port())
            );

            serverSession.start();
            clientSession.start();

            connection = clientSession.awaitConnection(30, TimeUnit.SECONDS);
            assertNotNull(connection);

            Path destination = Files.createTempFile("iroh-multipath-download-", ".txt");
            Path downloaded = requestFile(connection, hash.getBytes(StandardCharsets.UTF_8), destination);
            assertEquals(destination, downloaded);
            assertEquals("multi path", Files.readString(downloaded, StandardCharsets.UTF_8));

            assertTrue(waitForCarrierCount(clientEndpointIdHex, 2, 10, TimeUnit.SECONDS),
                "Expected raw TCP and minecraft tunnel carriers to stay registered for the same endpoint");

            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            String lastSnapshot = null;
            while (System.nanoTime() < deadlineNanos) {
                IrohPathInfo[] pathsSnapshot = connection.getPaths();
                String currentSnapshot = pl.skidam.automodpack_core.protocol.iroh.IrohPathSummary.describe(connection);
                if (!currentSnapshot.equals(lastSnapshot)) {
                    LOGGER.info("Observed multi-path test snapshot: {}", currentSnapshot);
                    lastSnapshot = currentSnapshot;
                }
                boolean hasIp = false;
                boolean hasMinecraft = false;
                int selectedCount = 0;
                for (IrohPathInfo pathInfo : pathsSnapshot) {
                    if (pathInfo.isSelected()) {
                        selectedCount++;
                    }
                    if (IrohPathInfo.KIND_IP.equals(pathInfo.getKind())) {
                        hasIp = true;
                    }
                    if (IrohPathInfo.KIND_CUSTOM.equals(pathInfo.getKind()) && Long.valueOf(IrohNode.MINECRAFT_CONNECTION_TRANSPORT_ID).equals(pathInfo.getCustomTransportId())) {
                        hasMinecraft = true;
                    }
                }
                if (hasIp && hasMinecraft) {
                    assertEquals(1, selectedCount, "Exactly one path should be selected");
                    return;
                }
                Thread.sleep(200);
            }

            LOGGER.warn(
                "Final multi-path test snapshot before failure: {} carrierCount={}",
                pl.skidam.automodpack_core.protocol.iroh.IrohPathSummary.describe(connection),
                hostServer.getConnectionCountsByEndpoint().getOrDefault(clientEndpointIdHex, 0)
            );
            fail("Did not observe direct IP and minecraft custom paths while both custom carriers were active");
        } finally {
            closeQuietly(connection);
            Thread.sleep(100);
            closeQuietly(clientSession);
            Thread.sleep(100);
            closeQuietly(serverSession);
            transportPair.close();
            bootstrapServer.close();
        }
    }

    private boolean waitForCarrierCount(String endpointIdHex, int minimumCount, long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadlineNanos) {
            if (hostServer.getConnectionCountsByEndpoint().getOrDefault(endpointIdHex, 0) >= minimumCount) {
                return true;
            }
            Thread.sleep(100);
        }
        return hostServer.getConnectionCountsByEndpoint().getOrDefault(endpointIdHex, 0) >= minimumCount;
    }

    private List<java.net.InetSocketAddress> waitForDirectAddresses() throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadlineNanos) {
            List<java.net.InetSocketAddress> directAddresses = hostServer.getIrohDirectAddresses();
            if (directAddresses != null && !directAddresses.isEmpty()) {
                return directAddresses;
            }
            Thread.sleep(100);
        }
        return List.of();
    }

    private static GameCallService allowOnly(String uuid) {
        return new GameCallService() {
            @Override
            public boolean isPlayerAuthorized(java.net.SocketAddress address, String id) {
                return uuid.equals(id);
            }
        };
    }

    private static byte[] fixedSecret(byte start) {
        byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) {
            secret[i] = (byte) (start + i);
        }
        return secret;
    }

    private static byte[] secretDifferentFrom(String endpointIdHex) {
        for (int seed = 1; seed < 255; seed++) {
            byte[] secret = fixedSecret((byte) seed);
            if (!IrohIdentity.toHex(IrohIdentity.deriveEndpointId(secret)).equals(endpointIdHex)) {
                return secret;
            }
        }
        throw new IllegalStateException("Failed to derive a distinct client endpoint id for the test");
    }

    private void overwriteSharedKeyForNextClient(byte[] secret) throws IOException {
        Files.createDirectories(IROH_KEY_FILE.getParent());
        Files.write(IROH_KEY_FILE, secret);
    }

    private void restoreIrohKey() {
        try {
            if (originalIrohKey == null) {
                Files.deleteIfExists(IROH_KEY_FILE);
            } else {
                Files.createDirectories(IROH_KEY_FILE.getParent());
                Files.write(IROH_KEY_FILE, originalIrohKey);
            }
        } catch (IOException e) {
            fail("Failed to restore shared iroh key after test: " + e.getMessage());
        }
    }

    private static Path requestFile(IrohConnection connection, byte[] fileHash, Path destination) throws Exception {
        IrohConnection.StreamOpenResult result = connection.openBiResult(IrohTransportSupport.STREAM_OPEN_TIMEOUT_MS);
        assertTrue(result.isOpened(), "Expected to open a bidirectional iroh stream");

        try (IrohBiStream stream = result.getStream()) {
            sendFileRequest(stream, fileHash);
            return readFileResponse(stream, destination, COMPRESSION_GZIP);
        }
    }

    private static void sendFileRequest(IrohBiStream stream, byte[] fileHash) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION);
            out.writeByte(FILE_REQUEST_TYPE);
            out.writeByte(COMPRESSION_GZIP);
            out.writeInt(DEFAULT_CHUNK_SIZE);
            out.writeInt(fileHash.length);
            out.write(fileHash);
        }

        long written = stream.write(bytes.toByteArray());
        assertEquals(bytes.size(), written, "Expected to write the full file request");
        assertTrue(stream.finish(), "Expected to finish the file request stream");
    }

    private static Path readFileResponse(IrohBiStream stream, Path destination, byte compressionType) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(IrohStreamAdapters.input(stream, IrohTransportSupport.STREAM_READ_TIMEOUT_MS)));
             OutputStream output = Files.newOutputStream(destination)) {
            byte[] headerData = readProtocolMessageFrame(in, compressionType);
            ByteBuffer header = ByteBuffer.wrap(headerData);

            assertEquals(LATEST_SUPPORTED_PROTOCOL_VERSION, header.get(), "Unexpected response protocol version");
            assertEquals(FILE_RESPONSE_TYPE, header.get(), "Unexpected response message type");
            long expectedFileSize = header.getLong();

            long receivedBytes = 0;
            while (receivedBytes < expectedFileSize) {
                byte[] dataFrame = readProtocolMessageFrame(in, compressionType);
                int bytesToWrite = Math.min(dataFrame.length, (int) (expectedFileSize - receivedBytes));
                output.write(dataFrame, 0, bytesToWrite);
                receivedBytes += bytesToWrite;
            }

            byte[] eotData = readProtocolMessageFrame(in, compressionType);
            assertTrue(eotData.length >= 2, "Expected end-of-transmission frame");
            assertEquals(LATEST_SUPPORTED_PROTOCOL_VERSION, eotData[0], "Unexpected EOT protocol version");
            assertEquals(END_OF_TRANSMISSION, eotData[1], "Unexpected EOT message type");
            return destination;
        }
    }

    private static byte[] readProtocolMessageFrame(DataInputStream in, byte compressionType) throws IOException {
        int compressedLength = in.readInt();
        int originalLength = in.readInt();
        byte[] compressed = in.readNBytes(compressedLength);
        CompressionCodec codec = CompressionFactory.getCodec(compressionType);
        return codec.decompress(compressed, originalLength);
    }

    private static void closeQuietly(AutoCloseable closeable) throws Exception {
        if (closeable != null) {
            closeable.close();
        }
    }

    private static final class InMemoryTransportPair implements AutoCloseable {
        private final InMemoryTransport left = new InMemoryTransport();
        private final InMemoryTransport right = new InMemoryTransport();

        private InMemoryTransportPair() {
            left.setPeer(right);
            right.setPeer(left);
        }

        AutoModpackConnectionTransport client() {
            return left;
        }

        AutoModpackConnectionTransport server() {
            return right;
        }

        @Override
        public void close() {
            left.close();
            right.close();
        }
    }

    private static final class InMemoryTransport implements AutoModpackConnectionTransport {
        private final Map<Byte, AutoModpackFrameHandler> handlers = new ConcurrentHashMap<>();
        private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "AutoModpackInMemoryTransport");
            thread.setDaemon(true);
            return thread;
        });
        private volatile InMemoryTransport peer;
        private volatile boolean open = true;

        void setPeer(InMemoryTransport peer) {
            this.peer = peer;
        }

        @Override
        public void registerHandler(byte kind, AutoModpackFrameHandler handler) {
            handlers.put(kind, handler);
        }

        @Override
        public void unregisterHandler(byte kind) {
            handlers.remove(kind);
        }

        @Override
        public void sendFrame(byte kind, ByteBuf payload) throws IOException {
            if (!open || peer == null || !peer.open) {
                payload.release();
                throw new IOException("Connection transport is closed");
            }

            AutoModpackFrameHandler handler = peer.handlers.get(kind);
            ByteBuf copy = Unpooled.copiedBuffer(payload);
            payload.release();
            if (handler == null) {
                copy.release();
                throw new IOException("No handler registered for frame kind " + kind);
            }

            peer.executor.execute(() -> {
                try {
                    AutoModpackFrameHandler activeHandler = peer.handlers.get(kind);
                    if (activeHandler != null && peer.open) {
                        activeHandler.handle(copy);
                    }
                } catch (Exception ignored) {
                } finally {
                    copy.release();
                }
            });
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        void close() {
            open = false;
            executor.shutdownNow();
        }
    }

    private static final class NettyBootstrapServer implements AutoCloseable {
        private final EventLoopGroup bossGroup;
        private final EventLoopGroup workerGroup;
        private final Channel channel;

        private NettyBootstrapServer(EventLoopGroup bossGroup, EventLoopGroup workerGroup, Channel channel) {
            this.bossGroup = bossGroup;
            this.workerGroup = workerGroup;
            this.channel = channel;
        }

        static NettyBootstrapServer start() throws InterruptedException {
            EventLoopGroup bossGroup = new NioEventLoopGroup(1);
            EventLoopGroup workerGroup = new NioEventLoopGroup(1);

            Channel channel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("automodpack-test", new ProtocolServerHandler());
                    }
                })
                .bind(0)
                .sync()
                .channel();

            return new NettyBootstrapServer(bossGroup, workerGroup, channel);
        }

        int port() {
            return ((java.net.InetSocketAddress) channel.localAddress()).getPort();
        }

        @Override
        public void close() throws InterruptedException {
            if (channel.isOpen()) {
                channel.close().sync();
            }
            workerGroup.shutdownGracefully().sync();
            bossGroup.shutdownGracefully().sync();
        }
    }
}
