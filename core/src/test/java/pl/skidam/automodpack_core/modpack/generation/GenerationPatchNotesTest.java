package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

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
	void fileNotesRequireStrictUtf8() throws Exception {
		Path file = tempDir.resolve("host-patch-notes.md");
		Files.write(file, new byte[]{(byte) 0xc3, (byte) 0x28});
		assertThrows(IOException.class, () -> GenerationPatchNotes.resolve(null, file));
		assertThrows(IllegalArgumentException.class,
				() -> new JournalEntry(1, "a".repeat(40), "b".repeat(40), Instant.now(), String.valueOf((char) 0xD800), JournalEntry.NO_RESTORE,
						List.of(JournalEntry.Change.added("config/example.txt", "c".repeat(40), 1))));
	}

	@Test
	void unchangedFileNotesCanBeConsumedAndChangedFileIsPreserved() throws Exception {
		Path unchanged = tempDir.resolve("unchanged.md");
		Files.writeString(unchanged, "notes\r\n", StandardCharsets.UTF_8);
		GenerationPatchNotes.Resolution first = GenerationPatchNotes.resolve(null, unchanged);
		assertEquals("notes\n", first.text());
		assertEquals(GenerationPatchNotes.CleanupStatus.CLEARED, first.consumeIfUnchanged().status());
		assertTrue(Files.exists(unchanged));
		assertEquals("", Files.readString(unchanged, StandardCharsets.UTF_8));

		Path changed = tempDir.resolve("changed.md");
		Files.writeString(changed, "before", StandardCharsets.UTF_8);
		GenerationPatchNotes.Resolution second = GenerationPatchNotes.resolve(null, changed);
		Files.writeString(changed, "after", StandardCharsets.UTF_8);
		GenerationPatchNotes.CleanupResult cleanup = second.consumeIfUnchanged();
		assertEquals(GenerationPatchNotes.CleanupStatus.PRESERVED_CHANGED, cleanup.status());
		assertEquals("after", Files.readString(changed, StandardCharsets.UTF_8));

		Path absent = tempDir.resolve("absent.md");
		Files.writeString(absent, "notes", StandardCharsets.UTF_8);
		GenerationPatchNotes.Resolution absentResolution = GenerationPatchNotes.resolve(null, absent);
		Files.delete(absent);
		assertEquals(GenerationPatchNotes.CleanupStatus.CREATED, absentResolution.consumeIfUnchanged().status());
		assertTrue(Files.exists(absent));
		assertEquals("", Files.readString(absent, StandardCharsets.UTF_8));

		Path empty = tempDir.resolve("empty.md");
		GenerationPatchNotes.ensurePresent(empty);
		assertEquals(GenerationPatchNotes.Source.EMPTY, GenerationPatchNotes.resolve(null, empty).source());
		Files.writeString(empty, "keep", StandardCharsets.UTF_8);
		GenerationPatchNotes.ensurePresent(empty);
		assertEquals("keep", Files.readString(empty, StandardCharsets.UTF_8));
	}
}
