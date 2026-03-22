package pl.skidam.automodpack_core.auth;

import org.junit.jupiter.api.Test;
import pl.skidam.automodpack_core.auth.dnssec.DnssecEndpointVerifier;
import pl.skidam.automodpack_core.auth.dnssec.DnssecVerificationRequest;
import pl.skidam.automodpack_core.auth.dnssec.DnssecVerificationResult;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.ModpackConnectionInfo;

import java.net.InetSocketAddress;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EndpointTrustVerifierTest {

    @Test
    void secureMatchesAcrossAllHostnameRoutesAutoTrust() {
        ModpackConnectionInfo connectionInfo = connectionInfo();
        Jsons.KnownHostsFieldsV2 knownHosts = new Jsons.KnownHostsFieldsV2();

        EndpointTrustDecision decision = EndpointTrustVerifier.evaluate(connectionInfo, knownHosts, verifier(
                Map.of(
                        "play.example", record("play.example", Jsons.DnssecStatus.SECURE_MATCH, connectionInfo.endpointId(), "ok"),
                        "mods.example", record("mods.example", Jsons.DnssecStatus.SECURE_MATCH, connectionInfo.endpointId(), "ok")
                )
        ));

        assertTrue(decision.isTrusted());
        assertEquals(TrustEvidence.DNSSEC_SIGNED_TXT, decision.trustEvidence());
    }

    @Test
    void dnssecMismatchHardFailsEvenForPreviouslyTrustedEndpoint() {
        ModpackConnectionInfo connectionInfo = connectionInfo();
        Jsons.KnownHostsFieldsV2 knownHosts = new Jsons.KnownHostsFieldsV2();
        knownHosts.trustedEndpoints.put("play.example:25565", new Jsons.TrustedEndpointRecord(
                connectionInfo.endpointId(),
                TrustEvidence.TOFU_MANUAL,
                1L,
                Map.of()
        ));

        EndpointTrustDecision decision = EndpointTrustVerifier.evaluate(connectionInfo, knownHosts, verifier(
                Map.of(
                        "play.example", record("play.example", Jsons.DnssecStatus.SECURE_MATCH, connectionInfo.endpointId(), "ok"),
                        "mods.example", record("mods.example", Jsons.DnssecStatus.SECURE_MISMATCH, "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100", "mismatch")
                )
        ));

        assertFalse(decision.isTrusted());
        assertTrue(decision.isHardFailure());
        assertEquals("mismatch", decision.reason());
    }

    @Test
    void insecureDnssecFallsBackToKnownTofuTrust() {
        ModpackConnectionInfo connectionInfo = connectionInfo();
        Jsons.KnownHostsFieldsV2 knownHosts = new Jsons.KnownHostsFieldsV2();
        knownHosts.trustedEndpoints.put("play.example:25565", new Jsons.TrustedEndpointRecord(
                connectionInfo.endpointId(),
                TrustEvidence.TOFU_KNOWN,
                1L,
                Map.of()
        ));

        EndpointTrustDecision decision = EndpointTrustVerifier.evaluate(connectionInfo, knownHosts, verifier(
                Map.of(
                        "play.example", record("play.example", Jsons.DnssecStatus.SECURE_MATCH, connectionInfo.endpointId(), "ok"),
                        "mods.example", record("mods.example", Jsons.DnssecStatus.INSECURE, connectionInfo.endpointId(), "unsigned")
                )
        ));

        assertTrue(decision.isTrusted());
        assertEquals(TrustEvidence.TOFU_KNOWN, decision.trustEvidence());
    }

    private static ModpackConnectionInfo connectionInfo() {
        return new ModpackConnectionInfo(
                new InetSocketAddress("play.example", 25565),
                "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
                java.util.List.of(),
                new InetSocketAddress("mods.example", 8443),
                false
        );
    }

    private static Jsons.DnssecDomainRecord record(String hostname, Jsons.DnssecStatus status, String endpointId, String reason) {
        return new Jsons.DnssecDomainRecord(
                hostname,
                "_automodpack." + hostname + ".",
                endpointId,
                status,
                1L,
                reason
        );
    }

    private static DnssecEndpointVerifier verifier(Map<String, Jsons.DnssecDomainRecord> result) {
        return request -> new DnssecVerificationResult(result);
    }
}
