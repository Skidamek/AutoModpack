package pl.skidam.automodpack_core.update;

import java.io.IOException;

/** A generation record's content is gone from the client object store: the shared data root was deleted, moved, or is not mounted. */
public class MissingGenerationContentException extends IOException {
	public MissingGenerationContentException(String message) {
		super(message);
	}
}
