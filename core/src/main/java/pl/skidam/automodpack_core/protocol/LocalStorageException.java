package pl.skidam.automodpack_core.protocol;

import java.io.IOException;

public class LocalStorageException extends IOException {
	public LocalStorageException(String message, IOException cause) {
		super(message, cause);
	}
}
