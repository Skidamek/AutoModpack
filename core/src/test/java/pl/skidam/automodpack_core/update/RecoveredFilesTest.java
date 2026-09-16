package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.storage.TestDataRoot;

class RecoveredFilesTest {
	private static final String FIRST = "a".repeat(40);
	private static final String SECOND = "b".repeat(40);

	@TempDir
	Path temporaryDirectory;

	@Test
	void recoveredCopyKeepsTheOriginalFolderAndNamesTheFileByClaim() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Path recovered = RecoveredFiles.path(storage, "config/local.txt", FIRST);
		assertEquals(storage.recoveredDirectory().resolve("config").resolve("local-" + FIRST.substring(0, 8) + ".txt"), recovered);
		assertNotEquals(RecoveredFiles.path(storage, "config/local.txt", SECOND), recovered);
	}
}
