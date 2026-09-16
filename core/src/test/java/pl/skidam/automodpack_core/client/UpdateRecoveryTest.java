package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.UpdateLoopDetector;

/** Pins the recovery policy's numbers: the helper's sleep budget receipt and the two-restarts-then-revert guard wiring. */
class UpdateRecoveryTest {

	@Test
	void helperRetryBudgetMatchesTheDefenderReceipt() {
		// 500ms→5s geometric over 19 sleeps: 500 + 1000 + 2000 + 4000 + 5000*15 = 82.5s, past Defender's 60s extended cloud check cap.
		assertEquals(82_500, UpdateRecovery.retryBudgetMillis());
	}

	@Test
	void deferredGuardRevertsOnTheThirdFailedRecover(@TempDir Path temp) throws Exception {
		ClientStorage storage = TestDataRoot.open(temp.resolve("game"), temp.resolve("data"));
		UpdateLoopDetector guard = UpdateRecovery.deferredGuard(storage);
		assertSame(UpdateLoopDetector.Decision.RESTART, guard.evaluateAndRecord("tx").decision());
		assertSame(UpdateLoopDetector.Decision.RESTART, guard.evaluateAndRecord("tx").decision());
		assertSame(UpdateLoopDetector.Decision.SUPPRESS, guard.evaluateAndRecord("tx").decision());

		// A different transaction id starts its own episode: the count never expires, but it is per transaction.
		UpdateLoopDetector fresh = UpdateRecovery.deferredGuard(storage);
		assertSame(UpdateLoopDetector.Decision.RESTART, fresh.evaluateAndRecord("other").decision());
	}
}
