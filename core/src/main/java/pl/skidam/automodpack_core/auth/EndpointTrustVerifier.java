package pl.skidam.automodpack_core.auth;

import pl.skidam.automodpack_core.auth.dnssec.DnsjavaDnssecEndpointVerifier;
import pl.skidam.automodpack_core.auth.dnssec.DnssecEndpointVerifier;
import pl.skidam.automodpack_core.auth.dnssec.DnssecVerificationRequest;
import pl.skidam.automodpack_core.auth.dnssec.DnssecVerificationResult;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.ModpackConnectionInfo;
import pl.skidam.automodpack_core.protocol.iroh.IrohIdentity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class EndpointTrustVerifier {
    private static final DnssecEndpointVerifier DNSSEC_VERIFIER = new DnsjavaDnssecEndpointVerifier();

    private EndpointTrustVerifier() {
    }

    public static EndpointTrustDecision evaluate(ModpackConnectionInfo connectionInfo, Jsons.KnownHostsFieldsV2 knownHosts) {
        return evaluate(connectionInfo, knownHosts, DNSSEC_VERIFIER);
    }

    static EndpointTrustDecision evaluate(ModpackConnectionInfo connectionInfo, Jsons.KnownHostsFieldsV2 knownHosts, DnssecEndpointVerifier dnssecVerifier) {
        if (connectionInfo == null || connectionInfo.minecraftServerAddress() == null || connectionInfo.endpointId() == null || connectionInfo.endpointId().isBlank() || knownHosts == null) {
            return new EndpointTrustDecision(TrustEvidence.NONE, false, null, Map.of());
        }

        knownHosts.normalize();

        DnssecVerificationResult dnssecResult = dnssecVerifier.verify(new DnssecVerificationRequest(
            connectionInfo.minecraftServerAddress(),
            connectionInfo.rawTcpAddress(),
            connectionInfo.endpointId()
        ));
        Map<String, Jsons.DnssecDomainRecord> dnssecDomains = new LinkedHashMap<>(dnssecResult.domainResults());

        boolean sawSecureMatch = false;
        boolean allRequiredSecure = !dnssecDomains.isEmpty();
        for (Jsons.DnssecDomainRecord domainRecord : dnssecDomains.values()) {
            if (domainRecord == null || domainRecord.status == null) {
                allRequiredSecure = false;
                continue;
            }

            switch (domainRecord.status) {
                case SECURE_MATCH -> sawSecureMatch = true;
                case SKIPPED_IP_LITERAL -> {
                    // IP literals are allowed routes but do not contribute DNSSEC evidence.
                }
                case SECURE_MISMATCH, BOGUS, MALFORMED -> {
                    String reason = domainRecord.reason == null || domainRecord.reason.isBlank()
                        ? "DNSSEC trust check failed for " + domainRecord.hostname
                        : domainRecord.reason;
                    return new EndpointTrustDecision(TrustEvidence.NONE, true, reason, dnssecDomains);
                }
                case INSECURE, NO_RECORD, NXDOMAIN, UNAVAILABLE -> allRequiredSecure = false;
            }
        }

        if (sawSecureMatch && allRequiredSecure) {
            return new EndpointTrustDecision(TrustEvidence.DNSSEC_SIGNED_TXT, false, null, dnssecDomains);
        }

        String key = IrohIdentity.canonicalServerKey(connectionInfo.minecraftServerAddress());
        Jsons.TrustedEndpointRecord trustedRecord = knownHosts.trustedEndpoints.get(key);
        if (trustedRecord != null && Objects.equals(trustedRecord.endpointId, connectionInfo.endpointId())) {
            TrustEvidence trustEvidence = trustedRecord.trustEvidence == null || trustedRecord.trustEvidence == TrustEvidence.NONE
                ? TrustEvidence.TOFU_KNOWN
                : trustedRecord.trustEvidence;
            return new EndpointTrustDecision(trustEvidence, false, null, dnssecDomains);
        }

        return new EndpointTrustDecision(TrustEvidence.NONE, false, null, dnssecDomains);
    }

    public static void trust(ModpackConnectionInfo connectionInfo, TrustEvidence trustEvidence, Map<String, Jsons.DnssecDomainRecord> dnssecDomains, Jsons.KnownHostsFieldsV2 knownHosts) {
        if (connectionInfo == null || connectionInfo.minecraftServerAddress() == null || connectionInfo.endpointId() == null || connectionInfo.endpointId().isBlank() || knownHosts == null) {
            return;
        }

        knownHosts.normalize();

        Jsons.TrustedEndpointRecord existingRecord = knownHosts.trustedEndpoints.get(IrohIdentity.canonicalServerKey(connectionInfo.minecraftServerAddress()));
        long trustedAt = existingRecord != null && existingRecord.trustedAt > 0 ? existingRecord.trustedAt : System.currentTimeMillis();
        Jsons.TrustedEndpointRecord updatedRecord = new Jsons.TrustedEndpointRecord(
            connectionInfo.endpointId(),
            trustEvidence,
            trustedAt,
            dnssecDomains
        );
        updatedRecord.normalize();
        knownHosts.trustedEndpoints.put(IrohIdentity.canonicalServerKey(connectionInfo.minecraftServerAddress()), updatedRecord);
    }
}
