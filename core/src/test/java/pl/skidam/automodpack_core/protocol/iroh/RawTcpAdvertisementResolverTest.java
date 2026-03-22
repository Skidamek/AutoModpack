package pl.skidam.automodpack_core.protocol.iroh;

import org.junit.jupiter.api.Test;
import pl.skidam.automodpack_core.config.Jsons;

import static org.junit.jupiter.api.Assertions.*;

class RawTcpAdvertisementResolverTest {

    @Test
    void explicitAddressAndPortOverrideDerivedValues() {
        Jsons.ServerConfigFieldsV2 serverConfig = new Jsons.ServerConfigFieldsV2();
        serverConfig.addressToSend = "mods.example";
        serverConfig.portToSend = 24454;
        serverConfig.bindPort = 25565;

        RawTcpAdvertisementResolver.Resolution resolved = RawTcpAdvertisementResolver.resolve(serverConfig, "play.example", 25565, true);

        assertNotNull(resolved);
        assertEquals("mods.example", resolved.address().getHostString());
        assertEquals(24454, resolved.address().getPort());
        assertEquals("explicit-config", resolved.source());
    }

    @Test
    void fallsBackToClientHandshakeOnSharedMinecraftPort() {
        Jsons.ServerConfigFieldsV2 serverConfig = new Jsons.ServerConfigFieldsV2();
        serverConfig.bindPort = -1;
        serverConfig.portToSend = -1;

        RawTcpAdvertisementResolver.Resolution resolved = RawTcpAdvertisementResolver.resolve(serverConfig, "play.example", 25565, true);

        assertNotNull(resolved);
        assertEquals("play.example", resolved.address().getHostString());
        assertEquals(25565, resolved.address().getPort());
        assertEquals("client-handshake-host+client-handshake-port", resolved.source());
    }

    @Test
    void fallsBackToBindPortWhenDedicatedRawPortRunsWithoutPortToSend() {
        Jsons.ServerConfigFieldsV2 serverConfig = new Jsons.ServerConfigFieldsV2();
        serverConfig.bindPort = 24454;
        serverConfig.portToSend = -1;

        RawTcpAdvertisementResolver.Resolution resolved = RawTcpAdvertisementResolver.resolve(serverConfig, "play.example", 25565, true);

        assertNotNull(resolved);
        assertEquals("play.example", resolved.address().getHostString());
        assertEquals(24454, resolved.address().getPort());
        assertEquals("client-handshake-host+bind-port", resolved.source());
    }

    @Test
    void returnsNullWhenRawTcpHostingIsUnavailable() {
        Jsons.ServerConfigFieldsV2 serverConfig = new Jsons.ServerConfigFieldsV2();
        serverConfig.addressToSend = "mods.example";
        serverConfig.portToSend = 24454;

        assertNull(RawTcpAdvertisementResolver.resolve(serverConfig, "play.example", 25565, false));
    }

    @Test
    void returnsNullWhenNoHostOrPortCanBeDerived() {
        Jsons.ServerConfigFieldsV2 serverConfig = new Jsons.ServerConfigFieldsV2();

        assertNull(RawTcpAdvertisementResolver.resolve(serverConfig, null, null, true));
    }
}
