package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_LOG_FILE;

import java.io.IOException;
import java.nio.file.Path;

import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdateLoopDetector;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;

/**
 * The blocked-transaction recovery policy, shared by the game process, the helper process, and boot: how long the
 * helper retries, how long boot waits out a running helper, when a deferred restart becomes a revert to the last
 * finalized generation, and what the player is told. The process mechanics (lease files, helper launch, restarts)
 * stay with {@link DetachedUpdateHelper}, {@link UpdateHelperMain}, and {@link ReLauncher}; this module owns only
 * the decisions and the numbers behind them.
 */
public final class UpdateRecovery {
	// Receipts: in-repo, 11s of retries lost an on-access lock race. Microsoft Defender cloud block holds a file 10s by default,
	// extendable to 60s (Configure extended cloud check). Geometric 500ms→5s over 20 attempts sleeps ~82.5s, past that 60s cap
	// with slack for a local archive scan of one jar (Defender's "expensive file" log threshold is 3s; there is no documented RTP cap).
	static final int MAX_ATTEMPTS = 20;
	static final long INITIAL_BACKOFF_MILLIS = 500;
	static final long MAX_BACKOFF_MILLIS = 5_000;
	// Two deferred restarts for the same transaction id; the third failed recover rolls back to the last finalized generation. The id is the episode; the count does not expire.
	static final int MAX_DEFERRED_RESTARTS = 2;
	// AWT preload dialog; Minecraft locale files are not loaded yet.
	private static final String DEFERRED_POPUP_MESSAGE = "The modpack update paused on a busy file. Close programs using the modpack folder, including File Explorer windows, then restart again; if this keeps appearing, please send your latest log file.";

	private UpdateRecovery() {}

	/** The helper's total sleep budget across a full retry loop, the number {@link DetachedUpdateHelper}'s lease wait is sized against. */
	static long retryBudgetMillis() {
		long total = 0;
		for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++)
			total += Math.min(MAX_BACKOFF_MILLIS, INITIAL_BACKOFF_MILLIS << (attempt - 1));
		return total;
	}

	static UpdateLoopDetector deferredGuard(ClientStorage storage) {
		return new UpdateLoopDetector(storage.stuckTransactionStateFile(), System::currentTimeMillis, MAX_DEFERRED_RESTARTS, null);
	}

	public static void clearDeferredGuard(ClientStorage storage) {
		deferredGuard(storage).clear();
	}

	/** One failed recovery attempt of a pending transaction and the policy's verdict on it. */
	public record RecoveryAttempt(UpdateTransactionExecutor.Execution execution, UpdateTransaction deferred, boolean reverted) {}

	/** One bounded recovery attempt against the pending transaction; IOException propagates to the caller's quarantine handling. */
	@FunctionalInterface
	public interface RecoveryRetry {
		UpdateTransactionExecutor.Execution recoverLatest() throws IOException;
	}

	/**
	 * The verdict on a failed recovery of a pending transaction: wait out a running helper and retry recovery once;
	 * otherwise evaluate the deferred-restart guard and either revert to the last finalized generation ({@code reverted},
	 * boot proceeds without the update) or launch a fresh helper and leave the deferral to the caller, which restarts
	 * with {@link #deferredPopupMessage}. The returned execution and transaction are the ones the caller finishes with.
	 */
	public static RecoveryAttempt blockedRecovery(ClientStorage storage, UpdateTransaction deferred, UpdateTransactionExecutor.Execution execution, RecoveryRetry recoverLatest)
			throws IOException {
		if (DetachedUpdateHelper.awaitRunningHelper()) {
			LOGGER.info("The detached update helper finished; retrying recovery of transaction {}", deferred.transactionId);
			execution = recoverLatest.recoverLatest();
			deferred = execution.transaction() == null ? deferred : execution.transaction();
			if (execution.success()) {
				DetachedUpdateHelper.mirrorRecentRuns();
				return new RecoveryAttempt(execution, deferred, false);
			}
		}
		UpdateLoopDetector.Outcome loop = deferredGuard(storage).evaluateAndRecord(deferred.transactionId);
		logDeferredRecovery(storage, deferred, execution, loop);
		if (loop.decision() == UpdateLoopDetector.Decision.SUPPRESS) {
			Path stuckJournal = UpdateTransactionSupport.executor().abandonStuckPublication(deferred);
			clearDeferredGuard(storage);
			LOGGER.error("The same update transaction {} failed after {} deferred restarts; kept the last finalized generation and retired the transaction to {}", deferred.transactionId,
					loop.restarts(), stuckJournal.toAbsolutePath().normalize());
			LOGGER.error("If the update keeps failing, send that file together with {} and the latest log", GameDirectory.current().resolve(HELPER_LOG_FILE).toAbsolutePath().normalize());
			return new RecoveryAttempt(execution, deferred, true);
		}
		DetachedUpdateHelper.launch();
		return new RecoveryAttempt(execution, deferred, false);
	}

	private static void logDeferredRecovery(ClientStorage storage, UpdateTransaction transaction, UpdateTransactionExecutor.Execution execution, UpdateLoopDetector.Outcome loop) {
		LOGGER.error("The pending modpack update did not finish: transaction {} (purpose {}, phase {}) ended with status {}", transaction.transactionId, transaction.purpose, transaction.phase,
				execution.status());
		LOGGER.error("Blocked operation {}, blocked path {}, message {}", execution.operation(), execution.blockedPath(), execution.message());
		LOGGER.error("Journal-recorded result: status {}, operation {}, path {}, message {}", transaction.resultStatus, transaction.resultOperation, transaction.resultPath, transaction.resultMessage);
		LOGGER.error("The full transaction journal is at {}", storage.transactionFile().toAbsolutePath().normalize());
		LOGGER.error("The detached helper's own log, with its per-attempt recovery failures, is at {}", GameDirectory.current().resolve(HELPER_LOG_FILE).toAbsolutePath().normalize());
		DetachedUpdateHelper.mirrorRecentRuns();
		if (loop.decision() == UpdateLoopDetector.Decision.RESTART)
			LOGGER.error("This is deferred restart {} of {} for the transaction; when they run out, the next failed launch rolls back to the last finalized generation", loop.restarts(), loop.maxRestarts());
	}

	/** The generic guidance, plus the lock probe's holder receipt when the failure captured one. */
	public static String deferredPopupMessage(String held) {
		return held == null || held.isBlank() ? DEFERRED_POPUP_MESSAGE : DEFERRED_POPUP_MESSAGE + " Blocked paths: " + held + ".";
	}
}
