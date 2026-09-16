package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableFilesTest {
	@TempDir
	Path tempDir;

	/** Simulates a filesystem without atomic rename; the plain-move fallback must still publish the temporary whole. */
	@Test
	void nonAtomicFallbackPublishesTheTemporary() throws IOException {
		Path target = tempDir.resolve("target.json");
		Files.writeString(target, "old", StandardCharsets.UTF_8);
		Path temporary = tempDir.resolve(".target.json.tmp");
		Files.writeString(temporary, "new", StandardCharsets.UTF_8);

		DurableFiles.replaceWithoutAtomicRename(temporary, target, new AtomicMoveNotSupportedException("source", "target", "simulated reason"));

		assertEquals("new", Files.readString(target, StandardCharsets.UTF_8));
		assertFalse(Files.exists(temporary));
	}
}
