package pl.skidam.automodpack_core.auth;

public enum TrustEvidence {
    TOFU_MANUAL,
    TOFU_KNOWN,
    DNSSEC_SIGNED_TXT,
    NONE
}
