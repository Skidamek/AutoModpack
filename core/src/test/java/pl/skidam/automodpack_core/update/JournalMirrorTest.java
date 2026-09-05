package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.modpack.generation.Journal;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.utils.HashUtils;

class JournalMirrorTest {
	private static final String MODPACK_ID = "abc1234";

	@TempDir
	Path temporaryDirectory;

	@Test
	void missingMirrorIsStaleAndReplaceFromInstallsTheFetchedReplica() throws Exception {
		ClientStorage storage = storage();
		JournalMirror mirror = new JournalMirror(storage);
		String headToken = sha1("head-content");
		Path fetched = fetchedJournal(storage, headToken, sha1("older-content"));

		assertTrue(mirror.isStale(MODPACK_ID, headToken));
		assertTrue(mirror.isStale(MODPACK_ID, sha1("other")));
		assertTrue(mirror.entries(MODPACK_ID).isEmpty());

		mirror.replaceFrom(MODPACK_ID, fetched);

		assertFalse(mirror.isStale(MODPACK_ID, headToken));
		assertTrue(mirror.isStale(MODPACK_ID, sha1("other")));
		assertEquals(headToken, mirror.lastEntryToken(MODPACK_ID).orElseThrow());
		assertEquals(2, mirror.entries(MODPACK_ID).size());
		assertFalse(Files.exists(fetched), "The fetched file is consumed by the atomic swap");
	}

	@Test
	void outOfDateOrUnreadableMirrorCountsAsStale() throws Exception {
		ClientStorage storage = storage();
		JournalMirror mirror = new JournalMirror(storage);
		Path fetched = fetchedJournal(storage, sha1("new-head"), sha1("older-content"));
		mirror.replaceFrom(MODPACK_ID, fetched);

		// A head token the mirror's last entry does not carry means the mirror lags the server.
		assertTrue(mirror.isStale(MODPACK_ID, sha1("newer-head")));
		assertFalse(mirror.isStale(MODPACK_ID, sha1("new-head")));

		Files.writeString(storage.historyJournalFile(MODPACK_ID), "{not a journal", StandardCharsets.UTF_8);
		assertTrue(mirror.isStale(MODPACK_ID, sha1("new-head")));
	}

	@Test
	void replaceFromRefusesAFetchedFileThatIsNotAJournal() throws Exception {
		ClientStorage storage = storage();
		JournalMirror mirror = new JournalMirror(storage);
		Path garbage = Files.createTempFile(storage.gameDirectory(), "journal-", ".temp");
		Files.writeString(garbage, "definitely not jsonl", StandardCharsets.UTF_8);

		assertThrows(RuntimeException.class, () -> mirror.replaceFrom(MODPACK_ID, garbage));

		assertFalse(mirror.exists(MODPACK_ID));
		assertTrue(mirror.entries(MODPACK_ID).isEmpty());
	}

	/** Builds a two-entry journal file the way the server's append-only journal looks, ending at {@code headToken}. */
	private static Path fetchedJournal(ClientStorage storage, String headToken, String olderToken) throws IOException {
		Path file = Files.createTempFile(storage.gameDirectory(), "fetched-journal-", ".jsonl");
		Journal journal = Journal.open(file);
		journal.append(new JournalEntry(1, olderToken, sha1("policy-one"), TestPacks.CREATED, "First", JournalEntry.NO_RESTORE, true, List.of()));
		journal.append(new JournalEntry(2, headToken, sha1("policy-two"), TestPacks.CREATED, "Second", JournalEntry.NO_RESTORE, false,
				List.of(new JournalEntry.Change("mods/test.jar", olderToken, headToken, 1))));
		return file;
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private static String sha1(String content) {
		return HashUtils.sha1(content.getBytes(StandardCharsets.UTF_8));
	}
}
