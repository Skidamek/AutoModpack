package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;

import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.utils.HashUtils;

class JournalTest {
	@TempDir
	Path tempDir;

	@Test
	void tornTailIsDroppedAndAppendingContinuesItsSequence() throws Exception {
		Path file = tempDir.resolve("journal.jsonl");
		Journal journal = Journal.open(file);
		journal.append(entry(1, "one"));
		journal.append(entry(2, "two"));
		String intact = Files.readString(file, StandardCharsets.UTF_8);
		Files.writeString(file, intact + "{\"seq\":3,\"cont", StandardCharsets.UTF_8);

		Journal reopened = Journal.open(file);
		assertEquals(2, reopened.length());
		assertEquals(2, reopened.head().seq());
		assertEquals(intact, Files.readString(file, StandardCharsets.UTF_8));

		reopened.append(entry(3, "three"));
		Journal afterAppend = Journal.open(file);
		assertEquals(3, afterAppend.length());
		assertEquals(3, afterAppend.head().seq());
	}

	@Test
	void tornNewlineAfterACompleteEntryIsRepairedInsteadOfFusingTheNextAppend() throws Exception {
		Path file = tempDir.resolve("journal.jsonl");
		Journal journal = Journal.open(file);
		journal.append(entry(1, "one"));
		String torn = Files.readString(file, StandardCharsets.UTF_8).stripTrailing();
		Files.writeString(file, torn, StandardCharsets.UTF_8);

		Journal reopened = Journal.open(file);
		assertEquals(1, reopened.length());

		reopened.append(entry(2, "two"));
		Journal afterAppend = Journal.open(file);
		assertEquals(2, afterAppend.length());
		assertEquals(2, afterAppend.head().seq());
	}

	@Test
	void malformedLineBeforeTheTailIsCorruption() throws Exception {
		Path file = tempDir.resolve("journal.jsonl");
		Journal journal = Journal.open(file);
		journal.append(entry(1, "one"));
		journal.append(entry(2, "two"));
		String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n");
		Files.writeString(file, lines[0] + "\nthis is not json\n" + lines[1] + "\n", StandardCharsets.UTF_8);

		IOException failure = assertThrows(IOException.class, () -> Journal.open(file));
		assertTrue(failure.getMessage().contains("Malformed journal line 2"));
	}

	@Test
	void corruptEntrySemanticsAreUnusableContentEvenAtTheTail() throws Exception {
		Path file = tempDir.resolve("journal.jsonl");
		Journal journal = Journal.open(file);
		journal.append(entry(1, "one"));
		String corrupt = Files.readString(file, StandardCharsets.UTF_8).stripTrailing().replace("\"seq\":1", "\"seq\":0");
		Files.writeString(file, corrupt, StandardCharsets.UTF_8);

		assertThrows(Journal.UnusableContentException.class, () -> Journal.open(file));
	}

	@Test
	void completeOpenRefusesATornTail() throws Exception {
		Path file = tempDir.resolve("journal.jsonl");
		Journal journal = Journal.open(file);
		journal.append(entry(1, "one"));
		Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8) + "{\"seq\":2,\"con", StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> Journal.openComplete(file));
	}

	@Test
	void removalsCarryTheirFreedSizeThroughTheJournalLine() {
		String sha1 = HashUtils.sha1("gone".getBytes(StandardCharsets.UTF_8));
		JournalEntry.Change parsed = JournalEntry.Change.fromFields(JournalEntry.Change.removed("mods/custom.jar", sha1, 54321).toFields());
		assertEquals(JournalEntry.Change.Kind.REMOVED, parsed.kind());
		assertEquals(sha1, parsed.fromSha1());
		assertEquals(54321, parsed.fromSize());
		assertEquals(0, parsed.toSize());
		assertEquals(0, JournalEntry.Change.fromFields(JournalEntry.Change.added("mods/new.jar", sha1, 7).toFields()).fromSize());
		assertThrows(IllegalArgumentException.class, () -> new JournalEntry.Change("mods/custom.jar", null, 5, sha1, 0));
	}

	@Test
	void journalLinesFromServersWithoutSourceSizesStillParse() {
		String sha1 = HashUtils.sha1("gone".getBytes(StandardCharsets.UTF_8));
		String line = "{\"path\":\"mods/custom.jar\",\"fromSha1\":\"" + sha1 + "\",\"toSha1\":\"\",\"toSize\":0}";
		JournalEntry.Change parsed = JournalEntry.Change.fromFields(new Gson().fromJson(line, GenerationJsons.JournalChangeFields.class));
		assertEquals(JournalEntry.Change.Kind.REMOVED, parsed.kind());
		assertEquals(sha1, parsed.fromSha1());
		assertEquals(0, parsed.fromSize());
		assertThrows(IllegalArgumentException.class, () -> new JournalEntry.Change("mods/custom.jar", sha1, -1, null, 0));
	}

	private static JournalEntry entry(long seq, String content) {
		String sha1 = HashUtils.sha1(content.getBytes(StandardCharsets.UTF_8));
		return new JournalEntry(seq, sha1, HashUtils.sha1(("policy-" + seq).getBytes(StandardCharsets.UTF_8)), TestPacks.CREATED, "Entry " + seq, JournalEntry.NO_RESTORE,
				List.of(new JournalEntry.Change("config/example.txt", null, 0, sha1, content.length())));
	}
}
