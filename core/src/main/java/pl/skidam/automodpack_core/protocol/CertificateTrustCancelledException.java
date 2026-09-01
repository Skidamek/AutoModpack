package pl.skidam.automodpack_core.protocol;

import java.io.IOException;

public class CertificateTrustCancelledException extends IOException {
	public CertificateTrustCancelledException() {
		super("Certificate verification cancelled");
	}

	public static boolean is(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof CertificateTrustCancelledException) return true;
		}
		return false;
	}
}
