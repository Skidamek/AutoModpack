package pl.skidam.automodpack_loader_core;

import static pl.skidam.automodpack_core.Constants.transactionFile;
import static pl.skidam.automodpack_core.Constants.transactionResultFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.update.UpdateExecutionException;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.update.UpdateTransactionResult;
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

			Path persistedPath = SmartFileUtils.CWD.resolve(transactionFile);
			UpdateTransaction transaction = ConfigTools.read(persistedPath, UpdateTransaction.class)
					.orElseThrow(() -> new IOException("Persisted update transaction is missing"));
			if (!expectedTransactionId.equals(transaction.transactionId)) throw new IOException("Persisted transaction UUID does not match helper invocation");

			UpdateTransactionExecutor executor = UpdateTransactionSupport.executor(transaction);
			executor.validate(transaction);
			long backoff = INITIAL_BACKOFF_MILLIS;
			for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
				UpdateTransactionExecutor.Execution execution = executor.recover(transaction);
				if (execution.success()) {
					writeResult(new UpdateTransactionResult(transaction.transactionId, UpdateTransactionResult.Status.SUCCESS, null, null, null));
					return 0;
				}
				if (attempt == MAX_ATTEMPTS) {
					writeResult(new UpdateTransactionResult(transaction.transactionId, UpdateTransactionResult.Status.FAILED, execution.operation(),
							execution.blockedPath() == null ? null : execution.blockedPath().toString(), execution.message()));
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
				writeResult(new UpdateTransactionResult(expectedTransactionId, UpdateTransactionResult.Status.FAILED, operation, path, failure.toString()));
			} catch (Exception ignored) {
				failure.addSuppressed(ignored);
			}
			failure.printStackTrace();
			return 1;
		}
	}

	private static void writeResult(UpdateTransactionResult result) throws IOException {
		ConfigTools.writeAtomic(SmartFileUtils.CWD.resolve(transactionResultFile), result);
	}
}
