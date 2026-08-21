package pl.skidam.automodpack_core.protocol;

import java.util.Objects;

public record DownloadFailure(Kind kind, Throwable cause) {
	public DownloadFailure {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(cause, "cause");
	}

	public enum Kind {
		REMOTE,
		LOCAL_STORAGE,
		PROTOCOL,
		INTEGRITY,
		CANCELLED
	}
}
