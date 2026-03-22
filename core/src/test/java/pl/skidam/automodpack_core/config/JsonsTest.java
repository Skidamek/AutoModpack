package pl.skidam.automodpack_core.config;

import org.junit.jupiter.api.Test;
import pl.skidam.automodpack_core.protocol.ModpackConnectionInfo;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.config.ConfigTools.GSON;

class JsonsTest {

    @Test
    void persistedIrohAddressBookRoundTripPreservesEndpointIdWithoutPersistingDirectAddresses() {
        Jsons.PersistedIrohAddressBook addressBook = new Jsons.PersistedIrohAddressBook(
                "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
                new InetSocketAddress("modpack.example", 8443),
                List.of(new InetSocketAddress("198.51.100.10", 8443)),
                123456789L
        );

        String json = GSON.toJson(addressBook);
        Jsons.PersistedIrohAddressBook decoded = GSON.fromJson(json, Jsons.PersistedIrohAddressBook.class);

        assertNotNull(decoded);
        assertEquals(addressBook.endpointId, decoded.endpointId);
        assertEquals(addressBook.rawTcp, decoded.rawTcp);
        assertNull(decoded.directIpAddresses);
        assertTrue(decoded.hasEndpointId());
        assertTrue(decoded.hasRawTcp());
    }

    @Test
    void persistedConnectionConvertsToRuntimeConnectionInfo() {
        Jsons.PersistedModpackConnection connection = new Jsons.PersistedModpackConnection(
                new InetSocketAddress("play.example", 25565),
                new Jsons.PersistedIrohAddressBook(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        null,
                        List.of(new InetSocketAddress("198.51.100.10", 8443)),
                        123L
                )
        );

        ModpackConnectionInfo runtime = ModpackConnectionInfo.fromPersisted(connection);

        assertNotNull(runtime);
        assertFalse(runtime.isUsable());
        assertTrue(runtime.hasEndpointId());
        assertFalse(runtime.hasRawTcpAddress());
        assertFalse(runtime.hasDirectIpAddresses());
        assertEquals(connection.minecraftServerAddress, runtime.minecraftServerAddress());
        assertEquals(connection.lastSuccessfulAddressBook.endpointId, runtime.endpointId());
    }

    @Test
    void knownHostsV2RoundTripPreservesEndpointIds() {
        Jsons.KnownHostsFieldsV2 knownHosts = new Jsons.KnownHostsFieldsV2();
        knownHosts.trustedEndpoints.put("play.example:25565", new Jsons.TrustedEndpointRecord(
                "endpoint-id",
                pl.skidam.automodpack_core.auth.TrustEvidence.DNSSEC_SIGNED_TXT,
                123L,
                Map.of("play.example", new Jsons.DnssecDomainRecord(
                        "play.example",
                        "_automodpack.play.example.",
                        "endpoint-id",
                        Jsons.DnssecStatus.SECURE_MATCH,
                        123L,
                        "ok"
                ))
        ));

        String json = GSON.toJson(knownHosts);
        Jsons.KnownHostsFieldsV2 decoded = GSON.fromJson(json, Jsons.KnownHostsFieldsV2.class);

        assertNotNull(decoded);
        assertEquals("endpoint-id", decoded.trustedEndpoints.get("play.example:25565").endpointId);
        assertEquals(pl.skidam.automodpack_core.auth.TrustEvidence.DNSSEC_SIGNED_TXT, decoded.trustedEndpoints.get("play.example:25565").trustEvidence);
        assertEquals(Jsons.DnssecStatus.SECURE_MATCH, decoded.trustedEndpoints.get("play.example:25565").dnssecDomains.get("play.example").status);
    }

    @Test
    void legacyKnownHostsMapMigratesToKnownHostsV2() {
        String legacyJson = """
                {
                  "trustedEndpoints": {
                    "play.example:25565": "endpoint-id"
                  }
                }
                """;

        Jsons.KnownHostsFieldsV2 decoded = Jsons.loadKnownHostsV2(legacyJson);

        assertNotNull(decoded);
        assertEquals("endpoint-id", decoded.trustedEndpoints.get("play.example:25565").endpointId);
        assertEquals(pl.skidam.automodpack_core.auth.TrustEvidence.TOFU_KNOWN, decoded.trustedEndpoints.get("play.example:25565").trustEvidence);
    }
}
