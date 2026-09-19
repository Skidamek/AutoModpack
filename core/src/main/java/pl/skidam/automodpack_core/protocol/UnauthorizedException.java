package pl.skidam.automodpack_core.protocol;

import java.io.IOException;

/** The server rejected the request's bearer secret with 401; retrying cannot help, the login flow must re-mint. */
public class UnauthorizedException extends IOException {

	public UnauthorizedException() {
		super("The server rejected the request's bearer secret");
	}
}
