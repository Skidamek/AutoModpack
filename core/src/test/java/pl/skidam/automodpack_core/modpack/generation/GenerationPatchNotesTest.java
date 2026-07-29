package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationPatchNotesTest {
	@TempDir
	Path tempDir;

	@Test
	void inlineNotesHavePrecedenceAndNormalizeLineEndings() throws Exception {
		Path file = tempDir.resolve("host-patch-notes.md");
		Files.writeString(file, "file notes", StandardCharsets.UTF_8);
		GenerationPatchNotes.Resolution notes = GenerationPatchNotes.resolve("inline\r\nnotes", file);
		assertEquals(GenerationPatchNotes.Source.INLINE, notes.source());
		assertEquals("inline\nnotes", notes.text());
		assertTrue(Files.exists(file));
	}

	@Test
	void fileNotesRequireStrictUtf8AndBoundedSize() throws Exception {
		Path file = tempDir.resolve("host-patch-notes.md");
		Files.write(file, new byte[]{(byte) 0xc3, (byte) 0x28});
		assertThrows(java.io.IOException.class, () -> GenerationPatchNotes.resolve(null, file));
		assertThrows(java.io.IOException.class, () -> GenerationPatchNotes.resolve(String.valueOf((char) 0xD800), file));

		Files.write(file, "x".repeat(GenerationPatchNotes.MAX_UTF8_BYTES + 1).getBytes(StandardCharsets.UTF_8));
		assertThrows(java.io.IOException.class, () -> GenerationPatchNotes.resolve(null, file));
	}

	@Test
	void unchangedFileNotesCanBeConsumedAndChangedFileIsPreserved() throws Exception {
		Path unchanged = tempDir.resolve("unchanged.md");
		Files.writeString(unchanged, "notes\r\n", StandardCharsets.UTF_8);
		GenerationPatchNotes.Resolution first = GenerationPatchNotes.resolve(null, unchanged);
		assertEquals("notes\n", first.text());
		assertEquals(GenerationPatchNotes.CleanupStatus.DELETED, first.consumeIfUnchanged().status());
		assertFalse(Files.exists(unchanged));

		Path changed = tempDir.resolve("changed.md");
		Files.writeString(changed, "before", StandardCharsets.UTF_8);
		GenerationPatchNotes.Resolution second = GenerationPatchNotes.resolve(null, changed);
		Files.writeString(changed, "after", StandardCharsets.UTF_8);
		GenerationPatchNotes.CleanupResult cleanup = second.consumeIfUnchanged();
		assertEquals(GenerationPatchNotes.CleanupStatus.PRESERVED_CHANGED, cleanup.status());
		assertTrue(Files.exists(changed));

		Path absent = tempDir.resolve("absent.md");
		Files.writeString(absent, "notes", StandardCharsets.UTF_8);
		GenerationPatchNotes.Resolution absentResolution = GenerationPatchNotes.resolve(null, absent);
		Files.delete(absent);
		assertEquals(GenerationPatchNotes.CleanupStatus.NOT_PRESENT, absentResolution.consumeIfUnchanged().status());
	}
}
