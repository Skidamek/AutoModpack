package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_LOG_FILE;

import java.io.IOException;
import java.nio.file.Path;

import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.PublicationSweep;
import pl.skidam.automodpack_core.update.UpdateCommit;
import pl.skidam.automodpack_core.update.UpdateLoopDetector;
import pl.skidam.automodpack_core.update.UpdateTransaction;

/**
 * The blocked-transaction recovery policy, shared by the game process, the helper process, and boot: how long the
 * helper retries, how long boot waits out a running helper, when deferred restarts stop and the game boots as it
 * stands, and what the player is told. The process mechanics (lease files, helper launch, restarts)
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
	// Two deferred restarts for the same transaction id; past them each launch retries once and boots as it stands. The id is the episode; the count does not expire.
	static final int MAX_DEFERRED_RESTARTS = 2;
	// From the third launch that boots as installed, the player is told too, not only the log.
	static final int BOOTS_AS_INSTALLED_BEFORE_NOTICE = 3;

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

	/** One failed recovery attempt of a pending transaction and the policy's verdict on it; {@code bootAsIs}: stop restarting and boot what is installed without contacting the server. */
	public record RecoveryAttempt(UpdateCommit.Outcome outcome, UpdateTransaction deferred, boolean bootAsIs) {}

	/** One bounded recovery attempt against the pending transaction; IOException propagates to the caller's quarantine handling. */
	@FunctionalInterface
	public interface RecoveryRetry {
		UpdateCommit.Outcome recover() throws IOException;
	}

	/**
	 * The verdict on a failed recovery of a pending transaction: wait out a running helper and retry recovery once;
	 * otherwise evaluate the deferred-restart guard and either boot as it stands ({@code bootAsIs}) or launch a fresh
	 * helper and leave the deferral to the caller, which restarts with {@link #deferredPopupMessage}. Nothing is rolled
	 * back: a started journal keeps what it still owes and every later launch retries it, while restoring its baselines
	 * would mix generations the way only a finished apply can untangle. The returned outcome and transaction are the
	 * ones the caller finishes with.
	 */
	public static RecoveryAttempt blockedRecovery(ClientStorage storage, UpdateTransaction deferred, UpdateCommit.Outcome outcome, RecoveryRetry recover) throws IOException {
		if (DetachedUpdateHelper.awaitRunningHelper()) {
			LOGGER.info("The detached update helper finished; retrying recovery of transaction {}", deferred.transactionId);
			outcome = recover.recover();
			deferred = outcome instanceof UpdateCommit.Blocked blocked ? blocked.transaction() : deferred;
			if (outcome.settled()) return new RecoveryAttempt(outcome, deferred, false);
		}
		if (outcome.status() == UpdateTransaction.Status.REPLAN_REQUIRED && outcome instanceof UpdateCommit.Blocked blocked) {
			// Only a journal that never wrote anything replans, and no restart can re-approve it - only the next sync's
			// fresh review can - so it retires at once instead of being deferred-restart counted.
			Path stuckJournal = PublicationSweep.retireJournal(storage);
			clearDeferredGuard(storage);
			LOGGER.error("The pending update {} needs a fresh review a restart cannot give ({}); retired the transaction to {}", deferred.transactionId, blocked.message(),
					stuckJournal == null ? "its already-retired journal" : stuckJournal.toAbsolutePath().normalize());
			return new RecoveryAttempt(outcome, deferred, true);
		}
		UpdateLoopDetector.Outcome loop = deferredGuard(storage).evaluateAndRecord(deferred.transactionId);
		logDeferredRecovery(storage, deferred, outcome, loop);
		if (loop.decision() == UpdateLoopDetector.Decision.SUPPRESS) {
			LOGGER.error("The same update transaction {} stayed blocked after {} deferred restarts and {} launches as installed; booting as installed, and every launch retries it",
					deferred.transactionId, loop.restarts(), loop.suppressed());
			if (loop.suppressed() >= BOOTS_AS_INSTALLED_BEFORE_NOTICE)
				SessionNotices.post("AutoModpack update not finished", "The modpack update has not finished for " + loop.suppressed()
						+ " launches, so the game runs the installed files. Close any program holding modpack files, or send AutoModpack's logs to the modpack author.");
			LOGGER.error("If the update keeps failing, send {} together with {} and the latest log", storage.transactionFile().toAbsolutePath().normalize(),
					GameDirectory.current().resolve(HELPER_LOG_FILE).toAbsolutePath().normalize());
			return new RecoveryAttempt(outcome, deferred, true);
		}
		handoffToHelper();
		return new RecoveryAttempt(outcome, deferred, false);
	}

	/**
	 * Starts the helper now, while this process is still alive. The helper waits for this PID to exit and then
	 * recovers; a shutdown hook is too late (Windows can skip it, and the JVM is already tearing down). A launch
	 * failure leaves the journal pending so the next boot retries.
	 */
	public static void handoffToHelper() {
		try {
			DetachedUpdateHelper.launch();
		} catch (IOException launchFailure) {
			LOGGER.error("Could not launch the detached update helper", launchFailure);
		}
	}

	private static void logDeferredRecovery(ClientStorage storage, UpdateTransaction transaction, UpdateCommit.Outcome outcome, UpdateLoopDetector.Outcome loop) {
		LOGGER.error("The pending modpack update did not finish: transaction {} (purpose {}, phase {}) ended with status {}", transaction.transactionId, transaction.purpose, transaction.phase,
				outcome.status());
		if (outcome instanceof UpdateCommit.Blocked blocked)
			LOGGER.error("Blocked operation {}, blocked path {}, message {}", blocked.operation(), blocked.blockedPath(), blocked.message());
		LOGGER.error("Journal-recorded result: status {}, operation {}, path {}, message {}", transaction.resultStatus, transaction.resultOperation, transaction.resultPath, transaction.resultMessage);
		LOGGER.error("The full transaction journal is at {}", storage.transactionFile().toAbsolutePath().normalize());
		LOGGER.error("The detached helper's own log, with its per-attempt recovery failures, is at {}", GameDirectory.current().resolve(HELPER_LOG_FILE).toAbsolutePath().normalize());
		if (loop.decision() == UpdateLoopDetector.Decision.RESTART)
			LOGGER.error("This is deferred restart {} of {} for the transaction; when they run out, each launch retries once and boots as installed", loop.restarts(), loop.maxRestarts());
	}

	/** AWT preload dialog; Minecraft locale files are not loaded yet. */
	public static String deferredPopupMessage(String held) {
		return "Some files are still open" + (held == null || held.isBlank() ? "" : " (" + held + ")") + ", so AutoModpack could not finish. Please start the game again.";
	}
}
