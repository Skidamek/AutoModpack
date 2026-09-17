package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStateJournal.Capture;
import pl.skidam.automodpack_core.update.ClientStateJournal.Change;
import pl.skidam.automodpack_core.update.ClientStateJournal.Kind;
import pl.skidam.automodpack_core.update.ClientStateJournal.StateEntry;
import pl.skidam.automodpack_core.update.ClientStateJournal.TrackedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.JsonLines;

class ClientStateJournalTest {
	private static final String MODPACK_ID = "abc1234";
	private static final Instant CREATED = Instant.parse("2026-09-17T12:00:00Z");

	@TempDir
	Path temporaryDirectory;

	@Test
	void checkpointsRoundTripAndEnforceSequence() throws Exception {
		ClientStateJournal journal = ClientStateJournal.open(journalFile());
		journal.append(entry(1, "txn-1", Kind.INSTALL));
		journal.append(entry(2, "txn-2", Kind.UPDATE));
		assertThrows(IOException.class, () -> journal.append(entry(2, "txn-3", Kind.UPDATE)));

		List<StateEntry> entries = ClientStateJournal.open(journalFile()).entries();
		assertEquals(2, entries.size());
		assertEquals(Kind.INSTALL, entries.get(0).kind());
		assertEquals("txn-2", entries.get(1).transactionId());
		assertEquals(List.of(new TrackedFile(Root.PROJECTION, "mods/a.jar", hash("a2"), 1)), entries.get(1).state());
	}

	@Test
	void appendTransactionDerivesTheKindAndDedupesOnTransactionId() throws Exception {
		ClientStateJournal journal = ClientStateJournal.open(journalFile());
		UpdateTransaction transaction = transaction("txn-1", plan());
		journal.appendTransaction(transaction);
		journal.appendTransaction(transaction);
		assertEquals(1, journal.entries().size());
		assertEquals(Kind.INSTALL, journal.head().kind());

		UpdateTransaction deactivation = transaction("txn-2", plan());
		deactivation.purpose = UpdateTransaction.Purpose.MODPACK_DEACTIVATION;
		journal.appendTransaction(deactivation);
		assertEquals(Kind.DEACTIVATION, journal.head().kind());

		ClientStateJournal reopened = ClientStateJournal.open(journalFile());
		reopened.appendTransaction(transaction("txn-3", plan()));
		assertEquals(Kind.UPDATE, reopened.head().kind());
		assertEquals(3, reopened.entries().size());
	}

	@Test
	void tornTailIsRepairedAndAppendContinues() throws Exception {
		ClientStateJournal journal = ClientStateJournal.open(journalFile());
		journal.append(entry(1, "txn-1", Kind.INSTALL));
		Files.writeString(journalFile(), Files.readString(journalFile(), StandardCharsets.UTF_8) + "{\"seq\":2,\"transactionId\":\"txn-2\",", StandardCharsets.UTF_8);

		ClientStateJournal reopened = ClientStateJournal.open(journalFile());
		assertEquals(1, reopened.entries().size());
		reopened.append(entry(2, "txn-2", Kind.UPDATE));
		assertTrue(Files.readString(journalFile(), StandardCharsets.UTF_8).endsWith("\n"));
		assertEquals(2, ClientStateJournal.open(journalFile()).entries().size());
	}

	@Test
	void contractBreaksAreCorruptionEvenAtTheTail() throws Exception {
		ClientStateJournal journal = ClientStateJournal.open(journalFile());
		journal.append(entry(1, "txn-1", Kind.INSTALL));
		Files.writeString(journalFile(), Files.readString(journalFile(), StandardCharsets.UTF_8) + "{\"seq\":-5,\"transactionId\":\"txn-2\"}", StandardCharsets.UTF_8);
		assertThrows(JsonLines.UnusableContentException.class, () -> ClientStateJournal.open(journalFile()));
	}

	@Test
	void unsortedOrRepeatingManifestsAreRejected() {
		TrackedFile second = new TrackedFile(Root.PROJECTION, "mods/b.jar", hash("b"), 1);
		TrackedFile first = new TrackedFile(Root.PROJECTION, "mods/a.jar", hash("a"), 1);
		assertThrows(IllegalArgumentException.class,
				() -> new StateEntry(1, "txn-1", Kind.INSTALL, MODPACK_ID, hash("token"), CREATED, StateEntry.NO_RESTORE, List.of(second, first), List.of(), List.of()));
		assertThrows(IllegalArgumentException.class,
				() -> new StateEntry(1, "txn-1", Kind.INSTALL, MODPACK_ID, hash("token"), CREATED, StateEntry.NO_RESTORE, List.of(first, first), List.of(), List.of()));
	}

	@Test
	void stateHistoryPinsReachTheReferenceSweep() throws Exception {
		ClientStorage storage = storage();
		String installed = store(storage, "installed-bytes");
		String replaced = store(storage, "player-file-bytes");
		ClientStateJournal journal = ClientStateJournal.open(storage.stateHistoryJournalFile());
		journal.append(new StateEntry(1, "txn-1", Kind.INSTALL, MODPACK_ID, hash("token"), CREATED, StateEntry.NO_RESTORE,
				List.of(new TrackedFile(Root.PROJECTION, "mods/installed.jar", installed, "installed-bytes".length())),
				List.of(Change.install(Root.PROJECTION, "mods/installed.jar", null, installed, "installed-bytes".length()), Change.removal(Root.GAME_DIR, "old/player.jar", replaced)),
				List.of(new Capture(Root.GAME_DIR, "old/player.jar", replaced, "player-file-bytes".length(), false))));

		Set<String> referenced = ClientObjectStore.referencedHashes(storage);
		assertTrue(referenced.contains(installed));
		assertTrue(referenced.contains(replaced));
	}

	private Path journalFile() {
		return temporaryDirectory.resolve("state-history").resolve("journal.jsonl");
	}

	private ClientStorage storage() throws Exception {
		return TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
	}

	private static String store(ClientStorage storage, String text) throws Exception {
		Path temporary = Files.createTempFile(storage.stagingDirectory(), "object-", ".tmp");
		Files.writeString(temporary, text, StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(temporary);
		ClientObjectStore.storeObject(storage, hash, text.getBytes(StandardCharsets.UTF_8));
		Files.delete(temporary);
		return hash;
	}

	private static String hash(String text) {
		return HashUtils.sha1(text.getBytes(StandardCharsets.UTF_8));
	}

	private static StateEntry entry(long seq, String transactionId, Kind kind) {
		return new StateEntry(seq, transactionId, kind, MODPACK_ID, hash("token-" + seq), CREATED, StateEntry.NO_RESTORE,
				List.of(new TrackedFile(Root.PROJECTION, "mods/a.jar", hash("a" + seq), 1)), List.of(), List.of());
	}

	private static UpdateTransaction transaction(String transactionId, UpdatePlan plan) {
		UpdateTransaction transaction = new UpdateTransaction();
		transaction.transactionId = transactionId;
		transaction.purpose = UpdateTransaction.Purpose.MODPACK_UPDATE;
		transaction.plan = plan;
		return transaction;
	}

	private static UpdatePlan plan() {
		PackTarget target = new PackTarget(MODPACK_ID, hash("token"), hash("policy"), hash("ledger"));
		return new UpdatePlan(MODPACK_ID, target, List.of(new Operation(Root.PROJECTION, "mods/a.jar", OperationType.INSTALL_OBJECT, hash("a"), 1, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/a.jar", true, hash("a"), 1)), null, Set.of(), List.of(), List.of(), List.of(), List.of(), ChangeSet.empty());
	}
}
