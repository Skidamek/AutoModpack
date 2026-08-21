package pl.skidam.automodpack_core.protocol;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.IntConsumer;

/** One file that a negotiated host transfer must place into a local destination. */
public record DownloadRequest(int itemId, String key, Path destination, long expectedFileSize, IntConsumer chunkCallback) implements DownloadBatchProtocol.Item {
	public DownloadRequest {
		if (itemId <= 0) throw new IllegalArgumentException("Download item ID must be positive");
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(destination, "destination");
		if (expectedFileSize < 0) throw new IllegalArgumentException("Expected file size must not be negative");
	}
}
