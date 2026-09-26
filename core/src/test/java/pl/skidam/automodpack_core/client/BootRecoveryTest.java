package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;

/** Pins the clean-boot contract of the boot ordering: defaults out, and a stale stuck-transaction guard cleared. */
class BootRecoveryTest {

	@Test
	void emptyStorageRecoversToDefaultsAndClearsAStaleGuard(@TempDir Path temp) throws Exception {
		ClientStorage storage = TestDataRoot.open(temp.resolve("game"), temp.resolve("data"));
		UpdateRecovery.deferredGuard(storage).evaluateAndRecord("stale");
		Path guardFile = storage.stuckTransactionStateFile();
		assertTrue(Files.exists(guardFile));

		BootRecovery.BootDecision decision = new BootRecovery(storage).recover();

		assertNotNull(decision.clientConfig());
		assertFalse(storage.hasSelectedModpack());
		assertFalse(decision.trustedBootstrapApply());
		assertFalse(decision.rolledBackStuckUpdate());
		assertFalse(Files.exists(guardFile));
	}
}
