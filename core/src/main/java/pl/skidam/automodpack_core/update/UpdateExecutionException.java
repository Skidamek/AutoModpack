package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Path;

public final class UpdateExecutionException extends IOException {
	private final String operation;
	private final Path path;

	public UpdateExecutionException(String operation, Path path, IOException cause) {
		super(cause.getMessage(), cause);
		this.operation = operation;
		this.path = path;
	}

	public String operation() {
		return operation;
	}

	public Path path() {
		return path;
	}
}
