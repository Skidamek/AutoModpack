package pl.skidam.automodpack_core.protocol.iroh;

import dev.iroh.IrohNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.skidam.automodpack_core.auth.PlayerEndpointsStore;
import pl.skidam.automodpack_core.loader.GameCallService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.skidam.automodpack_core.Constants.GAME_CALL;
import static pl.skidam.automodpack_core.Constants.playerEndpointsFile;
import static pl.skidam.automodpack_core.Constants.privateDir;

class IrohHostRuntimeSessionAuthTest {
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
    void endpointAuthorizationRequiresBoundUuid() throws Exception {
        try (IrohHostRuntime runtime = new IrohHostRuntime();
             IrohNode remoteNode = IrohNode.builder().relayMode(IrohNode.RelayMode.DISABLED).alpn("automodpack/1").build()) {
            byte[] peerId = remoteNode.getId();
            assertFalse(runtime.isEndpointAuthorized(peerId));
        }
    }

    @Test
    void endpointAuthorizationChecksGameCallByUuid() throws Exception {
        try (IrohHostRuntime runtime = new IrohHostRuntime();
             IrohNode remoteNode = IrohNode.builder().relayMode(IrohNode.RelayMode.DISABLED).alpn("automodpack/1").build()) {
            String allowedUuid = UUID.randomUUID().toString();
            GAME_CALL = new GameCallService() {
                @Override
                public boolean isPlayerAuthorized(java.net.SocketAddress address, String id) {
                    return allowedUuid.equals(id);
                }
            };

            byte[] peerId = remoteNode.getId();
            PlayerEndpointsStore.bindPlayer(allowedUuid, "Player", peerId);

            assertTrue(runtime.isEndpointAuthorized(peerId));
            GAME_CALL = new GameCallService() {
                @Override
                public boolean isPlayerAuthorized(java.net.SocketAddress address, String id) {
                    return false;
                }
            };
            assertFalse(runtime.isEndpointAuthorized(peerId));
        }
    }
}
