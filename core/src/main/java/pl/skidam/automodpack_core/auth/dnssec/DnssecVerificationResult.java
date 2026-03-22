package pl.skidam.automodpack_core.auth.dnssec;

import pl.skidam.automodpack_core.config.Jsons;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DnssecVerificationResult {
    private final Map<String, Jsons.DnssecDomainRecord> domainResults;

    public DnssecVerificationResult(Map<String, Jsons.DnssecDomainRecord> domainResults) {
        this.domainResults = domainResults == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(domainResults));
    }

    public Map<String, Jsons.DnssecDomainRecord> domainResults() {
        return domainResults;
    }
}
