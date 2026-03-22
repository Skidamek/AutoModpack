package pl.skidam.automodpack_core.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static pl.skidam.automodpack_core.Constants.playerEndpointsFile;

class PlayerEndpointsStoreTest {

    @BeforeEach
    void setUp() throws IOException {
        Files.deleteIfExists(playerEndpointsFile);
        PlayerEndpointsStore.clearForTests();
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(playerEndpointsFile);
        PlayerEndpointsStore.clearForTests();
    }

    @Test
    void replacesEndpointForSameUuidOnLogin() {
        String uuid = UUID.randomUUID().toString();

        PlayerEndpointsStore.bindPlayer(uuid, "Player", "endpoint-a");
        PlayerEndpointsStore.bindPlayer(uuid, "Player", "endpoint-b");

        assertEquals("endpoint-b", PlayerEndpointsStore.getEndpointIdForPlayer(uuid));
        assertNull(PlayerEndpointsStore.getPlayerUuidForEndpoint("endpoint-a"));
        assertEquals(uuid, PlayerEndpointsStore.getPlayerUuidForEndpoint("endpoint-b"));
    }

    @Test
    void movesEndpointToLatestUuidBinding() {
        String firstUuid = UUID.randomUUID().toString();
        String secondUuid = UUID.randomUUID().toString();

        PlayerEndpointsStore.bindPlayer(firstUuid, "First", "shared-endpoint");
        PlayerEndpointsStore.bindPlayer(secondUuid, "Second", "shared-endpoint");

        assertNull(PlayerEndpointsStore.getEndpointIdForPlayer(firstUuid));
        assertEquals("shared-endpoint", PlayerEndpointsStore.getEndpointIdForPlayer(secondUuid));
        assertEquals(secondUuid, PlayerEndpointsStore.getPlayerUuidForEndpoint("shared-endpoint"));
    }
}
