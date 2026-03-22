package pl.skidam.automodpack_core.auth.dnssec;

public interface DnssecEndpointVerifier {
    DnssecVerificationResult verify(DnssecVerificationRequest request);
}
