package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.util.Objects;

/** A certificate the trust ladder refused for good: this endpoint is never contacted again and no retry can change that. */
public class CertificateRejectedException extends IOException {
	/** Why the ladder refused it; the two cases need different words at the player and only one of them is fixable. */
	public enum Reason {
		/** The player said no on the manual trust prompt. */
		PLAYER,
		/** The CA that vouched for the leaf revoked it; accepting the certificate cannot repair that. */
		CA_REVOCATION
	}

	private final Reason reason;

	public CertificateRejectedException(Reason reason) {
		this(reason, reason == Reason.CA_REVOCATION ? "The certificate was revoked by its CA" : "User rejected certificate");
	}

	public CertificateRejectedException(Reason reason, String message) {
		super(message);
		this.reason = Objects.requireNonNull(reason, "reason");
	}

	public Reason reason() {
		return reason;
	}
}
