package pl.skidam.automodpack_core.protocol;

import dev.iroh.IrohNode;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.skidam.automodpack_core.auth.PlayerEndpointsStore;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.GameCallService;
import pl.skidam.automodpack_core.loader.NullGameCall;
import pl.skidam.automodpack_core.protocol.iroh.IrohIdentity;
import pl.skidam.automodpack_core.protocol.iroh.RawTcpIrohBootstrapClient;
import pl.skidam.automodpack_core.protocol.netty.handler.ProtocolServerHandler;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.ObservableMap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.Constants.GAME_CALL;
import static pl.skidam.automodpack_core.Constants.hostServer;
import static pl.skidam.automodpack_core.Constants.playerEndpointsFile;
import static pl.skidam.automodpack_core.Constants.privateDir;
import static pl.skidam.automodpack_core.Constants.serverConfig;

class IrohBootstrapIntegrationTest {
    private static final Path IROH_KEY_FILE = privateDir.resolve("iroh.key");
    private static final HexFormat HEX = HexFormat.of();

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

        assertTrue(hostServer.start(), "Hybrid host server should start its iroh runtime");
    }

    @AfterEach
    void tearDown() {
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
    void testIrohBootstrapAcknowledgedDownload() throws Exception {
        String hash = "test-hash";
        Path source = Files.createTempFile("iroh-source-", ".txt");
        Files.writeString(source, "hello over iroh", StandardCharsets.UTF_8);

        ObservableMap<String, Path> paths = new ObservableMap<>();
        paths.put(hash, source);
        hostServer.setPaths(paths);

        byte[] sharedSecret = fixedSecret((byte) 96);
        overwriteSharedKeyForNextClient(sharedSecret);
        byte[] clientEndpointId = IrohIdentity.deriveEndpointId(sharedSecret);
        String allowedUuid = UUID.randomUUID().toString();
        PlayerEndpointsStore.bindPlayer(allowedUuid, "Player", clientEndpointId);
        GAME_CALL = allowOnly(allowedUuid);

        try (NettyBootstrapServer bootstrapServer = NettyBootstrapServer.start();
             IrohDownloadClient client = new IrohDownloadClient(connectionInfoFor(bootstrapServer.port()), 1)) {
            Path destination = Files.createTempFile("iroh-download-", ".txt");
            Path downloaded = client.downloadFile(hash.getBytes(StandardCharsets.UTF_8), destination, null).get(20, TimeUnit.SECONDS);

            assertEquals(destination, downloaded);
            assertEquals("hello over iroh", Files.readString(downloaded, StandardCharsets.UTF_8));
        }
    }

    @Test
    void testBootstrapWithoutAcknowledgmentFails() throws Exception {
        try (java.net.ServerSocket serverSocket = new java.net.ServerSocket(0);
             IrohNode remoteNode = IrohNode.builder().relayMode(IrohNode.RelayMode.DISABLED).alpn("automodpack/1").build();
             IrohNode clientNode = IrohNode.builder().relayMode(IrohNode.RelayMode.DISABLED).alpn("automodpack/1").build()) {
            Thread serverThread = new Thread(() -> {
                try (var socket = serverSocket.accept(); InputStream in = socket.getInputStream()) {
                    in.readNBytes(64);
                } catch (Exception ignored) {
                }
            }, "NoAckBootstrapServer");
            serverThread.setDaemon(true);
            serverThread.start();

            IOException error = assertThrows(IOException.class, () ->
                    new RawTcpIrohBootstrapClient(
                            clientNode,
                            remoteNode.getId(),
                            AddressHelpers.format("127.0.0.1", serverSocket.getLocalPort())
                    )
            );
            assertTrue(error.getMessage().contains("bootstrap"), "Failure should mention bootstrap");
            serverThread.join(3000);
        }
    }

    @Test
    void testUnauthorizedBootstrapDoesNotRegisterPeer() throws Exception {
        try (NettyBootstrapServer bootstrapServer = NettyBootstrapServer.start();
             IrohNode clientNode = IrohNode.builder().relayMode(IrohNode.RelayMode.DISABLED).alpn("automodpack/1").build()) {
            GAME_CALL = allowOnly(UUID.randomUUID().toString());

            try (RawTcpIrohBootstrapClient ignored = new RawTcpIrohBootstrapClient(
                    clientNode,
                    IrohIdentity.fromHex(hostServer.getIrohEndpointId()),
                    connectionInfoFor(bootstrapServer.port()).rawTcpAddress()
            )) {
                // The client can finish its local bootstrap before the server rejects the endpoint on AMID.
            }
            Thread.sleep(200);
            assertTrue(hostServer.getConnectionCountsByEndpoint().isEmpty(), "Unauthorized bootstrap must not register a peer");
        }
    }

    @Test
    void testSharedIrohKeyCreatedAndReusedAcrossHostRestarts() throws Exception {
        hostServer.stop();
        Files.deleteIfExists(IROH_KEY_FILE);

        hostServer = new HybridHostServer();
        assertTrue(hostServer.start(), "Host should create the shared iroh key on startup");
        assertTrue(Files.exists(IROH_KEY_FILE), "Shared iroh key should be created");
        assertEquals(32L, Files.size(IROH_KEY_FILE), "Shared iroh key should be 32 bytes");
        String firstEndpointId = hostServer.getIrohEndpointId();

        hostServer.stop();
        hostServer = new HybridHostServer();
        assertTrue(hostServer.start(), "Host should restart using the persisted iroh key");
        assertEquals(firstEndpointId, hostServer.getIrohEndpointId(), "Host endpoint should remain stable across restarts");
    }

    @Test
    void testHostAndClientUseSameSharedIrohIdentity() throws Exception {
        byte[] sharedSecret = fixedSecret((byte) 7);
        restartHostWithSharedSecret(sharedSecret);
        byte[] expectedNodeId = IrohIdentity.fromHex(hostServer.getIrohEndpointId());

        try (IrohNode clientNode = IrohNode.builder()
                .secretKey(IrohIdentity.loadOrCreateSecret(IROH_KEY_FILE))
                .relayMode(IrohNode.RelayMode.DISABLED)
                .alpn("automodpack/1")
                .build()) {
            assertArrayEquals(expectedNodeId, clientNode.getId(), "Client should reuse the shared iroh identity");
        }
    }

    @Test
    void testInvalidSharedIrohKeyFailsWithoutOverwrite() throws Exception {
        hostServer.stop();

        byte[] invalidKey = new byte[] { 1, 2, 3, 4, 5 };
        Files.createDirectories(IROH_KEY_FILE.getParent());
        Files.write(IROH_KEY_FILE, invalidKey);

        hostServer = new HybridHostServer();
        assertFalse(hostServer.start(), "Host should reject an invalid shared iroh key");
        assertArrayEquals(invalidKey, Files.readAllBytes(IROH_KEY_FILE), "Invalid shared key must not be overwritten by host startup");

        IOException error = assertThrows(IOException.class, () ->
                new IrohDownloadClient(
                        new ModpackConnectionInfo(
                                AddressHelpers.format("127.0.0.1", 25565),
                                HEX.formatHex(new byte[32]),
                                java.util.List.of(),
                                AddressHelpers.format("127.0.0.1", 25565),
                                false
                        ),
                        1
                )
        );
        assertTrue(error.getMessage().contains("Invalid iroh secret key length"), "Client error should explain the invalid key");
        assertArrayEquals(invalidKey, Files.readAllBytes(IROH_KEY_FILE), "Invalid shared key must not be overwritten by client startup");
    }

    private static ModpackConnectionInfo connectionInfoFor(int port) {
        return new ModpackConnectionInfo(
                AddressHelpers.format("127.0.0.1", 25565),
                hostServer.getIrohEndpointId(),
                java.util.List.of(),
                AddressHelpers.format("127.0.0.1", port),
                false
        );
    }

    private void restartHostWithSharedSecret(byte[] secret) throws Exception {
        hostServer.stop();
        Files.createDirectories(IROH_KEY_FILE.getParent());
        Files.write(IROH_KEY_FILE, secret);
        hostServer = new HybridHostServer();
        assertTrue(hostServer.start(), "Host should start with the shared persisted iroh key");
    }

    private static byte[] fixedSecret(byte start) {
        byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) {
            secret[i] = (byte) (start + i);
        }
        return secret;
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

    private static GameCallService allowOnly(String uuid) {
        return new GameCallService() {
            @Override
            public boolean isPlayerAuthorized(java.net.SocketAddress address, String id) {
                return uuid.equals(id);
            }
        };
    }

    private static final class NettyBootstrapServer implements AutoCloseable {
        private final EventLoopGroup bossGroup;
        private final EventLoopGroup workerGroup;
        private final Channel channel;
        private final ChannelGroup channels;

        private NettyBootstrapServer(EventLoopGroup bossGroup, EventLoopGroup workerGroup, Channel channel, ChannelGroup channels) {
            this.bossGroup = bossGroup;
            this.workerGroup = workerGroup;
            this.channel = channel;
            this.channels = channels;
        }

        static NettyBootstrapServer start() throws InterruptedException {
            EventLoopGroup bossGroup = new NioEventLoopGroup(1);
            EventLoopGroup workerGroup = new NioEventLoopGroup(1);
            ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

            Channel channel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            channels.add(ch);
                            ch.pipeline().addLast("automodpack-test", new ProtocolServerHandler());
                        }
                    })
                    .bind(0)
                    .sync()
                    .channel();
            channels.add(channel);

            return new NettyBootstrapServer(bossGroup, workerGroup, channel, channels);
        }

        int port() {
            return ((java.net.InetSocketAddress) channel.localAddress()).getPort();
        }

        @Override
        public void close() throws InterruptedException {
            channels.close().sync();
            workerGroup.shutdownGracefully().sync();
            bossGroup.shutdownGracefully().sync();
        }
    }
}
