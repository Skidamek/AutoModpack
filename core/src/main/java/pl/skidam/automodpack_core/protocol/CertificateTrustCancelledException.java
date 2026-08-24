package pl.skidam.automodpack_core.protocol;

import java.io.IOException;

public class CertificateTrustCancelledException extends IOException {
	public CertificateTrustCancelledException() {
		super("Certificate verification cancelled");
	}
}
