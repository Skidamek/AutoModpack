package pl.skidam.automodpack_core.auth;

import pl.skidam.automodpack_core.config.Jsons;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EndpointTrustDecision {
    private final TrustEvidence trustEvidence;
    private final boolean hardFailure;
    private final String reason;
    private final Map<String, Jsons.DnssecDomainRecord> dnssecDomains;

    public EndpointTrustDecision(TrustEvidence trustEvidence, boolean hardFailure, String reason, Map<String, Jsons.DnssecDomainRecord> dnssecDomains) {
        this.trustEvidence = trustEvidence == null ? TrustEvidence.NONE : trustEvidence;
        this.hardFailure = hardFailure;
        this.reason = reason;
        this.dnssecDomains = dnssecDomains == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(dnssecDomains));
    }

    public TrustEvidence trustEvidence() {
        return trustEvidence;
    }

    public boolean isTrusted() {
        return trustEvidence != TrustEvidence.NONE && !hardFailure;
    }

    public boolean isHardFailure() {
        return hardFailure;
    }

    public boolean requiresManualTrust() {
        return trustEvidence == TrustEvidence.NONE && !hardFailure;
    }

    public String reason() {
        return reason;
    }

    public Map<String, Jsons.DnssecDomainRecord> dnssecDomains() {
        return dnssecDomains;
    }
}
