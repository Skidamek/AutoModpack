package pl.skidam.automodpack_loader_core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdateExecutionException;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

public final class UpdateHelperMain {
	private static final int MAX_ATTEMPTS = 8;
	private static final long INITIAL_BACKOFF_MILLIS = 250;
	private static final long MAX_BACKOFF_MILLIS = 2_000;

	private UpdateHelperMain() {}

	public static void main(String[] arguments) {
		int exitCode = run(arguments);
		if (exitCode != 0) System.exit(exitCode);
	}

	static int run(String[] arguments) {
		String expectedTransactionId = arguments.length == 2 ? arguments[1] : null;
		try {
			if (arguments.length != 2) throw new IOException("Expected parent PID and transaction UUID");
			long parentPid = Long.parseLong(arguments[0]);
			UUID.fromString(expectedTransactionId);
			if (parentPid <= 0 || parentPid == ProcessHandle.current().pid()) throw new IOException("Invalid parent PID");
			ProcessHandle.of(parentPid).ifPresent(parent -> parent.onExit().join());

			ClientStorage storage = ClientStorage.fromGameDirectory(SmartFileUtils.CWD);
			Path persistedPath = storage.transactionFile();
			UpdateTransaction transaction = ConfigTools.read(persistedPath, UpdateTransaction.class)
					.orElseThrow(() -> new IOException("Persisted update transaction is missing"));
			if (!expectedTransactionId.equals(transaction.transactionId)) throw new IOException("Persisted transaction UUID does not match helper invocation");

			UpdateTransactionExecutor executor = UpdateTransactionSupport.executor();
			executor.validate(transaction);
			long backoff = INITIAL_BACKOFF_MILLIS;
			for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
				UpdateTransactionExecutor.Execution execution = executor.recover(transaction);
				if (execution.success()) {
					return 0;
				}
				if (attempt == MAX_ATTEMPTS) {
					recordFailure(storage, transaction, execution.operation(), execution.blockedPath() == null ? null : execution.blockedPath().toString(), execution.message());
					return 1;
				}
				Thread.sleep(backoff);
				backoff = Math.min(MAX_BACKOFF_MILLIS, backoff * 2);
			}
			throw new IOException("Update helper exhausted retries");
		} catch (Exception failure) {
			String operation = null;
			String path = null;
			if (failure instanceof UpdateExecutionException executionFailure) {
				operation = executionFailure.operation();
				path = executionFailure.path() == null ? null : executionFailure.path().toString();
			}
			try {
				ClientStorage storage = ClientStorage.fromGameDirectory(SmartFileUtils.CWD);
				UpdateTransaction transaction = ConfigTools.read(storage.transactionFile(), UpdateTransaction.class).orElse(null);
				if (transaction != null && expectedTransactionId.equals(transaction.transactionId)) recordFailure(storage, transaction, operation, path, failure.toString());
			} catch (Exception ignored) {
				failure.addSuppressed(ignored);
			}
			failure.printStackTrace();
			return 1;
		}
	}

	private static void recordFailure(ClientStorage storage, UpdateTransaction transaction, String operation, String path, String message) throws IOException {
		transaction.resultStatus = UpdateTransaction.Status.FAILED;
		transaction.resultOperation = operation;
		transaction.resultPath = path;
		transaction.resultMessage = message;
		ConfigTools.writeAtomic(storage.transactionFile(), transaction);
	}
}
