package pl.skidam.automodpack_core.update;

public final class UpdateTransactionResult {
	public String transactionId;
	public Status status;
	public String operation;
	public String path;
	public String message;

	public UpdateTransactionResult() {}

	public UpdateTransactionResult(String transactionId, Status status, String operation, String path, String message) {
		this.transactionId = transactionId;
		this.status = status;
		this.operation = operation;
		this.path = path;
		this.message = message;
	}

	public enum Status {
		SUCCESS,
		DEFERRED_LOCKED,
		FAILED
	}
}
