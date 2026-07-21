package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Path;

public class UpdateDeferredException extends IOException {
	private final String transactionId;
	private final Path blockedPath;

	public UpdateDeferredException(String transactionId, Path blockedPath, String message) {
		super(message);
		this.transactionId = transactionId;
		this.blockedPath = blockedPath;
	}

	public String getTransactionId() {
		return transactionId;
	}

	public Path getBlockedPath() {
		return blockedPath;
	}
}
