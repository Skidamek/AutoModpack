package pl.skidam.automodpack_core.protocol;

import java.io.IOException;

/** The player's explicit no on the trust ladder's manual prompt: final for this endpoint, never re-prompted or re-probed. */
public class UserRejectedCertificateException extends IOException {
	public UserRejectedCertificateException() {
		super("User rejected certificate");
	}
}
