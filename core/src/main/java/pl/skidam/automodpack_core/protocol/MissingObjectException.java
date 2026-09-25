package pl.skidam.automodpack_core.protocol;

import java.io.IOException;

/** The server answered 404 or 410: it does not store this object or document, and retrying cannot change that. */
public class MissingObjectException extends IOException {

	public MissingObjectException() {
		super("The server does not store the requested object");
	}
}
