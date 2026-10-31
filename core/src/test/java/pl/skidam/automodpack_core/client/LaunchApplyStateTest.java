package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;

class LaunchApplyStateTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void unverifiedLaunchWritesDefaultOffAndRoundTrip() throws Exception {
		ClientStorage storage = storage("consent");
		assertFalse(LaunchApplyState.allowsUnverifiedLaunchWrites(storage, "abc1234"));
		LaunchApplyState.setAllowsUnverifiedLaunchWrites(storage, "abc1234", true);
		assertTrue(LaunchApplyState.allowsUnverifiedLaunchWrites(storage, "abc1234"));
		LaunchApplyState.setAllowsUnverifiedLaunchWrites(storage, "abc1234", false);
		assertFalse(LaunchApplyState.allowsUnverifiedLaunchWrites(storage, "abc1234"));
	}

	@Test
	void theUnseenApplyReceiptIsReadableWithoutBeingTaken() throws Exception {
		ClientStorage storage = storage("receipt");
		assertFalse(LaunchApplyState.hasUnseenApply(storage, "abc1234"));
		LaunchApplyState.recordUnseenApply(storage, "abc1234");
		assertTrue(LaunchApplyState.hasUnseenApply(storage, "abc1234"));
		assertTrue(LaunchApplyState.hasUnseenApply(storage, "abc1234"), "reading the receipt does not consume it");
		assertTrue(LaunchApplyState.takeUnseenApply(storage, "abc1234"));
		assertFalse(LaunchApplyState.hasUnseenApply(storage, "abc1234"));
		assertFalse(LaunchApplyState.takeUnseenApply(storage, "abc1234"));
	}

	private ClientStorage storage(String name) throws Exception {
		return TestDataRoot.open(Files.createDirectory(temporaryDirectory.resolve("game-" + name)), Files.createDirectory(temporaryDirectory.resolve("data-" + name)));
	}
}
