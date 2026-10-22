package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdateCommit;
import pl.skidam.automodpack_core.update.UpdateLoopDetector;
import pl.skidam.automodpack_core.update.UpdateTransaction;

/** Pins the recovery policy's numbers: the helper's sleep budget receipt and the two-restarts-then-boot-as-installed guard wiring. */
class UpdateRecoveryTest {

	@Test
	void helperRetryBudgetMatchesTheDefenderReceipt() {
		// 500ms→5s geometric over 19 sleeps: 500 + 1000 + 2000 + 4000 + 5000*15 = 82.5s, past Defender's 60s extended cloud check cap.
		assertEquals(82_500, UpdateRecovery.retryBudgetMillis());
	}

	@Test
	void deferredGuardStopsRestartingOnTheThirdFailedRecover(@TempDir Path temp) throws Exception {
		ClientStorage storage = TestDataRoot.open(temp.resolve("game"), temp.resolve("data"));
		UpdateLoopDetector guard = UpdateRecovery.deferredGuard(storage);
		assertSame(UpdateLoopDetector.Decision.RESTART, guard.evaluateAndRecord("tx").decision());
		assertSame(UpdateLoopDetector.Decision.RESTART, guard.evaluateAndRecord("tx").decision());
		assertSame(UpdateLoopDetector.Decision.SUPPRESS, guard.evaluateAndRecord("tx").decision());

		// A different transaction id starts its own episode: the count never expires, but it is per transaction.
		UpdateLoopDetector fresh = UpdateRecovery.deferredGuard(storage);
		assertSame(UpdateLoopDetector.Decision.RESTART, fresh.evaluateAndRecord("other").decision());
	}

	@Test
	void pastItsRestartsAStillHeldUpdateBootsAsInstalledAndStaysOwed(@TempDir Path temp) throws Exception {
		ClientStorage storage = TestDataRoot.open(temp.resolve("game"), temp.resolve("data"));
		UpdateTransaction owed = new UpdateTransaction();
		owed.transactionId = "tx";
		Files.createDirectories(storage.transactionFile().getParent());
		Files.writeString(storage.transactionFile(), "{}");
		UpdateRecovery.deferredGuard(storage).evaluateAndRecord("tx");
		UpdateRecovery.deferredGuard(storage).evaluateAndRecord("tx");
		UpdateCommit.Deferred held = new UpdateCommit.Deferred(owed, "INSTALL_OBJECT", storage.gamePath("config/held.toml"), "held", null);

		for (int launch = 0; launch < 2; launch++) {
			UpdateRecovery.RecoveryAttempt attempt = UpdateRecovery.blockedRecovery(storage, owed, held, () -> held);
			assertTrue(attempt.bootAsIs());
		}

		// Nothing rolls back and nothing retires: every later launch retries the same owed journal once, without a restart loop.
		assertTrue(Files.isRegularFile(storage.transactionFile()));
		assertSame(UpdateLoopDetector.Decision.SUPPRESS, UpdateRecovery.deferredGuard(storage).evaluateAndRecord("tx").decision());
	}

	@Test
	void thePlayerIsToldFromTheThirdLaunchThatBootsAsInstalled(@TempDir Path temp) throws Exception {
		ClientStorage storage = TestDataRoot.open(temp.resolve("game"), temp.resolve("data"));
		UpdateTransaction owed = new UpdateTransaction();
		owed.transactionId = "tx";
		UpdateRecovery.deferredGuard(storage).evaluateAndRecord("tx");
		UpdateRecovery.deferredGuard(storage).evaluateAndRecord("tx");
		UpdateCommit.Deferred held = new UpdateCommit.Deferred(owed, "INSTALL_OBJECT", storage.gamePath("config/held.toml"), "held", null);
		SessionNotices.take();

		for (int launch = 1; launch <= 4; launch++) {
			assertTrue(UpdateRecovery.blockedRecovery(storage, owed, held, () -> held).bootAsIs());
			List<SessionNotices.Notice> notices = SessionNotices.take();
			assertEquals(launch >= UpdateRecovery.BOOTS_AS_INSTALLED_BEFORE_NOTICE ? 1 : 0, notices.size(), "launch " + launch);
			if (!notices.isEmpty()) assertTrue(notices.get(0).message().contains(launch + " launches"));
		}
	}
}
