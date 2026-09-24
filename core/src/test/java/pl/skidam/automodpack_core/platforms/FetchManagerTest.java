package pl.skidam.automodpack_core.platforms;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FetchManagerTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void cancelledLookupIsAnAbortNotACompletedMiss() throws Exception {
		try (PlatformCache cache = PlatformCache.open(temporaryDirectory)) {
			FetchManager manager = new FetchManager(List.of(new FetchManager.FetchData("mods/a.jar", "a".repeat(40), null, "mod")), cache);
			manager.fetchAsync();
			manager.cancel();
			InterruptedException abort = assertThrows(InterruptedException.class, manager::fetch);
			assertTrue(abort.getMessage().contains("cancelled"));
			assertTrue(manager.isCancelled());
			assertTrue(Thread.interrupted());
		}
	}
}
