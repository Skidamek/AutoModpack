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
	void completeOpenRefusesATornTail() throws Exception {
		Path file = tempDir.resolve("journal.jsonl");
		Journal journal = Journal.open(file);
		journal.append(entry(1, "one"));
		Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8) + "{\"seq\":2,\"con", StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> Journal.openComplete(file));
	}

	private static JournalEntry entry(long seq, String content) {
		String sha1 = HashUtils.sha1(content.getBytes(StandardCharsets.UTF_8));
		return new JournalEntry(seq, sha1, HashUtils.sha1(("policy-" + seq).getBytes(StandardCharsets.UTF_8)), TestPacks.CREATED, "Entry " + seq, JournalEntry.NO_RESTORE,
				List.of(new JournalEntry.Change("config/example.txt", null, sha1, content.length())));
	}
}
