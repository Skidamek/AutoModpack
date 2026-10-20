package pl.skidam.automodpack_core.protocol;

import java.io.IOException;

/** The host answered a full-object take whose declared length is not the expected object size: it served the wrong object, and retrying cannot change that. */
public class ObjectSizeMismatchException extends IOException {

	public ObjectSizeMismatchException(long servedLength, long expectedSize, String path) {
		super("Served object length " + servedLength + " does not match the expected object size " + expectedSize + " for " + path);
	}
}
