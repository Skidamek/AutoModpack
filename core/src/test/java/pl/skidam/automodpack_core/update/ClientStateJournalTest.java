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
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
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
	void aCorruptInteriorLineIsAsidedAndTheJournalContinuesEmpty() throws Exception {
		ClientStateJournal journal = ClientStateJournal.open(journalFile());
		journal.append(entry(1, "txn-1", Kind.INSTALL));
		Files.writeString(journalFile(), Files.readString(journalFile(), StandardCharsets.UTF_8) + "{\"seq\":-5,\"transactionId\":\"txn-2\"}\n", StandardCharsets.UTF_8);

		ClientStateJournal reopened = ClientStateJournal.open(journalFile());
		assertTrue(reopened.entries().isEmpty(), "Corrupt content reads as empty after the aside");
		assertTrue(Files.list(journalFile().getParent()).anyMatch(path -> path.getFileName().toString().contains("corrupt")), "The corrupt evidence stays");
		reopened.append(entry(1, "txn-3", Kind.UPDATE));
		assertEquals(1, ClientStateJournal.open(journalFile()).entries().size());
	}

	@Test
	void unsortedOrRepeatingManifestsAreRejected() {
		TrackedFile second = new TrackedFile(Root.PROJECTION, "mods/b.jar", hash("b"), 1);
		TrackedFile first = new TrackedFile(Root.PROJECTION, "mods/a.jar", hash("a"), 1);
		assertThrows(IllegalArgumentException.class,
				() -> new StateEntry(1, "txn-1", Kind.INSTALL, MODPACK_ID, hash("token"), CREATED, StateEntry.NO_RESTORE, List.of(second, first), List.of(), List.of()));
		assertThrows(IllegalArgumentException.class,
				() -> new StateEntry(1, "txn-1", Kind.INSTALL, MODPACK_ID, hash("token"), CREATED, StateEntry.NO_RESTORE, List.of(first, first), List.of(), List.of()));
		TrackedFile samePathDifferentHash = new TrackedFile(Root.PROJECTION, "mods/a.jar", hash("other"), 1);
		assertThrows(IllegalArgumentException.class,
				() -> new StateEntry(1, "txn-1", Kind.INSTALL, MODPACK_ID, hash("token"), CREATED, StateEntry.NO_RESTORE, List.of(first, samePathDifferentHash), List.of(), List.of()));
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

	@Test
	void theDeclaredStateKindBeatsThePurposeMapping() throws Exception {
		ClientStateJournal journal = ClientStateJournal.open(journalFile());
		UpdateTransaction rollback = transaction("txn-1", plan());
		rollback.stateKind = ClientStateJournal.Kind.ROLLBACK.name();
		journal.appendTransaction(rollback);
		assertEquals(1, journal.entries().size());
		assertEquals(Kind.ROLLBACK, journal.head().kind());
	}

	@Test
	void fileRestoreGatesAppendsACheckpointAndSavesCopies() throws Exception {
		ClientStorage storage = storage();
		String bytes = "player-mod";
		String hash = store(storage, bytes);
		ClientStateJournal journal = ClientStateJournal.open(storage.stateHistoryJournalFile());
		journal.append(new StateEntry(1, "txn-1", Kind.UPDATE, MODPACK_ID, hash("token"), CREATED, StateEntry.NO_RESTORE,
				List.of(new TrackedFile(Root.GAME_DIR, "oldmods/player.jar", hash, bytes.length())), List.of(), List.of()));

		// Projection files have no game-directory path to restore to.
		assertThrows(IOException.class, () -> StateHistory.restoreFile(storage, 1, Root.PROJECTION, "mods/player.jar"));

		Path restored = StateHistory.restoreFile(storage, 1, Root.GAME_DIR, "oldmods/player.jar");
		assertEquals("player-mod", Files.readString(restored, StandardCharsets.UTF_8));
		List<StateEntry> entries = StateHistory.entries(storage);
		assertEquals(2, entries.size());
		assertEquals(Kind.FILE_RESTORE, entries.get(1).kind());
		assertEquals(1, entries.get(1).restoreOfSeq());
		assertEquals(hash, entries.get(1).state().get(0).sha1());
		assertEquals(JournalEntry.Change.Kind.CHANGED, entries.get(1).changes().get(0).kind());
		assertEquals(hash, entries.get(1).changes().get(0).fromSha1());

		// Restoring refuses to overwrite a different live file.
		Files.writeString(restored, "changed by hand", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> StateHistory.restoreFile(storage, 1, Root.GAME_DIR, "oldmods/player.jar"));

		Path copy = StateHistory.saveFileCopy(storage, 1, Root.GAME_DIR, "oldmods/player.jar");
		assertEquals("player-mod", Files.readString(copy, StandardCharsets.UTF_8));
		assertTrue(copy.toString().contains("recovered"));
	}

	@Test
	void restorabilityRoutesByKindAndActivePack() throws Exception {
		ClientStorage storage = storage();
		ClientStateJournal journal = ClientStateJournal.open(storage.stateHistoryJournalFile());
		journal.append(new StateEntry(1, "txn-1", Kind.UPDATE, MODPACK_ID, hash("token"), CREATED, StateEntry.NO_RESTORE,
				List.of(new TrackedFile(Root.PROJECTION, "mods/a.jar", hash("a"), 1)), List.of(), List.of()));
		journal.append(new StateEntry(2, "file-1", Kind.FILE_RESTORE, MODPACK_ID, hash("token"), CREATED, 1,
				List.of(new TrackedFile(Root.PROJECTION, "mods/a.jar", hash("a"), 1), new TrackedFile(Root.GAME_DIR, "oldmods/player.jar", hash("p"), 1)), List.of(), List.of()));

		assertEquals(StateHistory.Restorability.INACTIVE_PACK, StateHistory.restorability(storage, StateHistory.entry(storage, 1)).restorability());
		assertEquals(StateHistory.Restorability.MIXED, StateHistory.restorability(storage, StateHistory.entry(storage, 2)).restorability());
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
