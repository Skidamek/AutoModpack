package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;

/** The helper writes a status document before releasing its lease; the game log never parses last-helper-run.log. */
class DetachedUpdateHelperTest {

	@Test
	void recoveredStatusIsMirroredAndConsumed(@TempDir Path temp) throws Exception {
		Path file = temp.resolve("last-helper-status.json");
		DetachedUpdateHelper.writeRunStatus(file, false, "1.2s", List.of());
		DetachedUpdateHelper.HelperStatusFields status = ConfigTools.readCached(file, DetachedUpdateHelper.HelperStatusFields.class, "Helper run status", Function.identity()).orElseThrow();
		assertFalse(status.failed);
		assertEquals("1.2s", status.duration);
		assertTrue(status.details.isEmpty());

		DetachedUpdateHelper.mirrorRecentRuns(file);
		assertFalse(Files.exists(file));
	}

	@Test
	void failedStatusCarriesOnlyFailureDetails(@TempDir Path temp) throws Exception {
		Path file = temp.resolve("last-helper-status.json");
		DetachedUpdateHelper.writeRunStatus(file, true, "3.0s", List.of("Self-update recovery failed: locked", "Update helper gave up; the transaction stays pending"));
		DetachedUpdateHelper.HelperStatusFields status = ConfigTools.readCached(file, DetachedUpdateHelper.HelperStatusFields.class, "Helper run status", Function.identity()).orElseThrow();
		assertTrue(status.failed);
		assertEquals("3.0s", status.duration);
		assertEquals(List.of("Self-update recovery failed: locked", "Update helper gave up; the transaction stays pending"), status.details);
	}
}
