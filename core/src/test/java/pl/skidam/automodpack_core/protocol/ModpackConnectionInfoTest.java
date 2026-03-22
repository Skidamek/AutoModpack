package pl.skidam.automodpack_core.protocol;

import org.junit.jupiter.api.Test;
import pl.skidam.automodpack_core.config.Jsons;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModpackConnectionInfoTest {

    @Test
    void fromPersistedPreservesRawTcpButDropsPersistedDirectAddresses() {
        Jsons.PersistedModpackConnection persisted = new Jsons.PersistedModpackConnection(
            new InetSocketAddress("play.example", 25565),
            new Jsons.PersistedIrohAddressBook(
                "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
                new InetSocketAddress("mods.example", 24454),
                List.of(new InetSocketAddress("198.51.100.20", 24454)),
                123L
            )
        );

        ModpackConnectionInfo connectionInfo = ModpackConnectionInfo.fromPersisted(persisted);

        assertNotNull(connectionInfo);
        assertEquals("play.example", connectionInfo.minecraftServerAddress().getHostString());
        assertEquals("mods.example", connectionInfo.rawTcpAddress().getHostString());
        assertTrue(connectionInfo.directIpAddresses().isEmpty());
        assertTrue(connectionInfo.hasRawTcpAddress());
        assertTrue(connectionInfo.isUsable());
    }

    @Test
    void toPersistedModpackConnectionOmitsShareMinecraftConnectionFlagAndDirectAddresses() {
        ModpackConnectionInfo connectionInfo = new ModpackConnectionInfo(
            new InetSocketAddress("play.example", 25565),
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
            List.of(new InetSocketAddress("198.51.100.20", 24454)),
            new InetSocketAddress("mods.example", 24454),
            true
        );

        Jsons.PersistedModpackConnection persisted = connectionInfo.toPersistedModpackConnection();

        assertNotNull(persisted);
        assertEquals(connectionInfo.endpointId(), persisted.lastSuccessfulAddressBook.endpointId);
        assertEquals(connectionInfo.rawTcpAddress(), persisted.lastSuccessfulAddressBook.rawTcp);
        assertNull(persisted.lastSuccessfulAddressBook.directIpAddresses);
        assertEquals(connectionInfo.minecraftServerAddress(), persisted.minecraftServerAddress);
    }
}
