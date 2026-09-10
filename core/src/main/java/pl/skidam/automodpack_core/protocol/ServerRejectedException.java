package pl.skidam.automodpack_core.protocol;

import java.io.IOException;

/** The server answered the negotiation with an error frame: the rejection is the server's verdict, so retrying cannot change it. */
public class ServerRejectedException extends IOException {
	public ServerRejectedException(String message) {
		super(message);
	}
}
