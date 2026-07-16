package pl.skidam.automodpack_core.protocol;

import java.security.cert.CertificateException;

public class CertificatePinMismatchException extends CertificateException {
	private final String origin;
	private final String expectedFingerprint;
	private final String presentedFingerprint;

	public CertificatePinMismatchException(String origin, String expectedFingerprint, String presentedFingerprint) {
		super("Certificate pin mismatch for " + origin);
		this.origin = origin;
		this.expectedFingerprint = expectedFingerprint;
		this.presentedFingerprint = presentedFingerprint;
	}

	public String getOrigin() {
		return origin;
	}

	public String getExpectedFingerprint() {
		return expectedFingerprint;
	}

	public String getPresentedFingerprint() {
		return presentedFingerprint;
	}
}
