package pl.skidam.automodpack_core.protocol;

import java.util.Objects;
import java.util.Optional;

public record DownloadResult(DownloadRequest request, Optional<DownloadFailure> failure) {
	public DownloadResult {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(failure, "failure");
	}

	public static DownloadResult success(DownloadRequest request) {
		return new DownloadResult(request, Optional.empty());
	}

	public static DownloadResult failure(DownloadRequest request, DownloadFailure.Kind kind, Throwable cause) {
		return new DownloadResult(request, Optional.of(new DownloadFailure(kind, cause)));
	}

	public boolean success() {
		return failure.isEmpty();
	}
}
