package pl.skidam.automodpack_core.protocol;

import dev.iroh.IrohNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.skidam.automodpack_core.auth.PlayerEndpointsStore;
import pl.skidam.automodpack_core.loader.GameCallService;
import pl.skidam.automodpack_core.protocol.iroh.IrohHostRuntime;
import pl.skidam.automodpack_core.protocol.iroh.IrohIdentity;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.Constants.GAME_CALL;
import static pl.skidam.automodpack_core.Constants.playerEndpointsFile;
import static pl.skidam.automodpack_core.Constants.privateDir;

class HybridHostServerSessionRegistrationTest {
    private static final Path IROH_KEY_FILE = privateDir.resolve("iroh.key");

    private byte[] originalIrohKey;
    private GameCallService previousGameCall;

    @BeforeEach
    void setUp() throws IOException {
        previousGameCall = GAME_CALL;
        originalIrohKey = Files.exists(IROH_KEY_FILE) ? Files.readAllBytes(IROH_KEY_FILE) : null;
        Files.deleteIfExists(playerEndpointsFile);
        PlayerEndpointsStore.clearForTests();
    }

    @AfterEach
    void tearDown() throws IOException {
        GAME_CALL = previousGameCall;
        Files.deleteIfExists(playerEndpointsFile);
        PlayerEndpointsStore.clearForTests();
        if (originalIrohKey == null) {
            Files.deleteIfExists(IROH_KEY_FILE);
        } else {
            Files.createDirectories(IROH_KEY_FILE.getParent());
            Files.write(IROH_KEY_FILE, originalIrohKey);
        }
    }

    @Test
    void unregisteringOneSessionKeepsOtherSessionTrackingForSamePeer() throws Exception {
        HybridHostServer hostServer = new HybridHostServer();
        IrohHostRuntime runtime = getRuntime(hostServer);

        try (IrohNode remoteNode = IrohNode.builder().relayMode(IrohNode.RelayMode.DISABLED).alpn("automodpack/1").build()) {
            assertTrue(runtime.start(), "Iroh host runtime should start");

            byte[] peerId = remoteNode.getId();
            String endpointId = IrohIdentity.toHex(peerId);
            Object firstSession = new Object();
            Object secondSession = new Object();

            assertNotNull(hostServer.bootstrapIrohPeer(firstSession, peerId));
            assertNotNull(hostServer.bootstrapIrohPeer(secondSession, peerId));

            Map<String, Integer> counts = hostServer.getConnectionCountsByEndpoint();
            assertEquals(2, counts.get(endpointId));

            hostServer.unregisterIrohBootstrap(firstSession);
            assertEquals(1, hostServer.getConnectionCountsByEndpoint().get(endpointId));

            hostServer.unregisterIrohBootstrap(secondSession);
            assertFalse(hostServer.getConnectionCountsByEndpoint().containsKey(endpointId));
        } finally {
            runtime.close();
        }
    }

    @Test
    void endpointAuthorizationUsesUuidBinding() throws Exception {
        HybridHostServer hostServer = new HybridHostServer();
        IrohHostRuntime runtime = getRuntime(hostServer);

        try (IrohNode remoteNode = IrohNode.builder().relayMode(IrohNode.RelayMode.DISABLED).alpn("automodpack/1").build()) {
            assertTrue(runtime.start(), "Iroh host runtime should start");

            String allowedUuid = UUID.randomUUID().toString();
            GAME_CALL = new GameCallService() {
                @Override
                public boolean isPlayerAuthorized(java.net.SocketAddress address, String id) {
                    return allowedUuid.equals(id);
                }
            };

            byte[] peerId = remoteNode.getId();
            PlayerEndpointsStore.bindPlayer(allowedUuid, "Player", peerId);

            assertTrue(hostServer.isEndpointAuthorized(peerId));
            assertFalse(hostServer.isEndpointAuthorized(new byte[32]));
        } finally {
            runtime.close();
        }
    }

    private static IrohHostRuntime getRuntime(HybridHostServer hostServer) throws Exception {
        Field runtimeField = HybridHostServer.class.getDeclaredField("irohRuntime");
        runtimeField.setAccessible(true);
        return (IrohHostRuntime) runtimeField.get(hostServer);
    }
}
