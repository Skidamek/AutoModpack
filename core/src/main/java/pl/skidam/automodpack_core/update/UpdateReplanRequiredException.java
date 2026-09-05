package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Path;

/** Indicates that mutable filesystem input changed and the durable intent must be replanned. */
public final class UpdateReplanRequiredException extends IOException {
	private final Path changedPath;

	public UpdateReplanRequiredException(Path changedPath, String message) {
		super(message);
		this.changedPath = changedPath;
	}

	public UpdateReplanRequiredException(Path changedPath, String message, Throwable cause) {
		super(message, cause);
		this.changedPath = changedPath;
	}

	public Path changedPath() {
		return changedPath;
	}
}
