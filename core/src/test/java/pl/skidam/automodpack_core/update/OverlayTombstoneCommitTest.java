package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;

/** #580 failure 1 and its siblings: every commit door derives the same tombstones, and a delete with nothing to remove is complete. */
class OverlayTombstoneCommitTest {
	private static final String PACK = "abc1234";
	private static final String TOMBSTONED = "config/example-client.toml";
	private static final String KEPT = "config/kept.txt";
	private static final byte[] KEPT_BYTES = "kept\n".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path temporaryDirectory;

	/** The journal rc.4 and rc.5 left behind for #580 failure 1: the planned tombstone delete threw NoSuchFileException mid-apply. */
	@ParameterizedTest
	@ValueSource(strings = {"rc.4", "rc.5"})
	void aReleaseCandidateJournalStuckOnATombstoneDeleteRecovers(String release) throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		String kept = store(storage, KEPT_BYTES);
		UpdateTestFixtures.target(storage, KEPT, "config", false, kept, KEPT_BYTES.length);
		storage.writeOverlayState(PACK, Set.of(TOMBSTONED));
		// The release's live installs ran before its delete threw.
		Files.createDirectories(storage.gamePath(KEPT).getParent());
		Files.write(storage.gamePath(KEPT), KEPT_BYTES);
		installFixture(storage, release + "-tombstone-delete-failed.json");
		UpdateTransaction stuck = UpdateTransaction.read(storage.transactionFile());
		assertEquals(UpdateTransaction.Status.FAILED, stuck.resultStatus);

		UpdateCommit.Outcome recovered = UpdateTestFixtures.commits(storage).run(new UpdateCommit.Recover(null));

		assertInstanceOf(UpdateCommit.Applied.class, recovered);
		assertFalse(Files.exists(storage.transactionFile()));
		assertArrayEquals(KEPT_BYTES, Files.readAllBytes(storage.activePath(KEPT)));
		// The derived tombstone set spends the deleted path's tombstone, on the recovery path too (S2).
		assertTrue(storage.readOverlayState(PACK).deletedPaths.isEmpty());
		assertInstanceOf(UpdateCommit.Idle.class, UpdateTestFixtures.commits(storage).run(new UpdateCommit.Recover(null)));
	}

	@Test
	void aFreshPlanCannotDeleteWhatItNeverObserved() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		String kept = store(storage, KEPT_BYTES);
		SelectedModpackTarget target = UpdateTestFixtures.target(storage, KEPT, "config", false, kept, KEPT_BYTES.length);
		UpdatePlan plan = UpdateTestFixtures.plan(target, sorted(new Operation(Root.PROJECTION, KEPT, OperationType.INSTALL_OBJECT, kept, KEPT_BYTES.length, null),
				new Operation(Root.OVERLAY, TOMBSTONED, OperationType.DELETE, null, -1, null)), finalState(kept, new ProjectedFile(Root.OVERLAY, TOMBSTONED, false, null, -1)));

		var refused = assertThrows(IOException.class, () -> UpdateTestFixtures.commit(storage, plan, target));

		assertTrue(refused.getMessage().contains("no baseline"), refused.getMessage());
		assertFalse(Files.exists(storage.activeDirectory()));
	}

	@Test
	void theCommitKeepsOnlyTombstonesTheTargetShipsAsEditable() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		String kept = store(storage, KEPT_BYTES);
		SelectedModpackTarget target = UpdateTestFixtures.target(storage, KEPT, "config", true, kept, KEPT_BYTES.length);
		UpdatePlan plan = UpdateTestFixtures.plan(target, sorted(new Operation(Root.PROJECTION, KEPT, OperationType.INSTALL_OBJECT, kept, KEPT_BYTES.length, null)),
				finalState(kept, new ProjectedFile(Root.OVERLAY, KEPT, false, null, -1)));
		storage.writeOverlayState(PACK, Set.of("config/stale.toml", KEPT));
		ConfigTools.writeAtomic(storage.transactionFile(), UpdateTestFixtures.createTransaction(storage, plan, target));
		assertInstanceOf(UpdateCommit.Applied.class, UpdateTestFixtures.commits(storage).run(new UpdateCommit.Recover(null)));

		assertEquals(List.of(KEPT), storage.readOverlayState(PACK).deletedPaths);
		assertFalse(Files.exists(storage.gamePath(KEPT)), "a tombstoned editable path keeps its live copy absent");
	}

	@Test
	void aPublishedReplayDoesNotCompareTombstonesThePlanItselfRewrites() throws Exception {
		// S4: the post-publication overlay compare used to require the stale tombstones, so a replay that cleaned them was "drift".
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		String kept = store(storage, KEPT_BYTES);
		SelectedModpackTarget target = UpdateTestFixtures.target(storage, KEPT, "config", false, kept, KEPT_BYTES.length);
		storage.writeOverlayState(PACK, Set.of(TOMBSTONED));
		UpdatePlan plan = UpdateTestFixtures.plan(target, sorted(new Operation(Root.PROJECTION, KEPT, OperationType.INSTALL_OBJECT, kept, KEPT_BYTES.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, KEPT, true, kept, KEPT_BYTES.length)));
		UpdateTransaction transaction = UpdateTestFixtures.createTransaction(storage, plan, target);
		transaction.phase = UpdateTransaction.Phase.SWAPPING;
		ConfigTools.writeAtomic(storage.transactionFile(), transaction);

		assertInstanceOf(UpdateCommit.Applied.class, UpdateTestFixtures.commits(storage).run(new UpdateCommit.Recover(cause -> {
			throw new AssertionError("a published replay must not rebuild: " + cause.message());
		})));
		assertTrue(storage.readOverlayState(PACK).deletedPaths.isEmpty());
	}

	/** defer() and a replan persist DEFERRED before any write: such a journal is still a plan, so a later overlay edit is drift, not this apply's progress. */
	@ParameterizedTest
	@ValueSource(strings = {"DEFERRED_LOCKED", "REPLAN_REQUIRED"})
	void aJournalDeferredBeforeItsFirstWriteSeesALaterOverlayEdit(String status) throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		String kept = store(storage, KEPT_BYTES);
		SelectedModpackTarget target = UpdateTestFixtures.target(storage, KEPT, "config", true, kept, KEPT_BYTES.length);
		UpdatePlan plan = UpdateTestFixtures.plan(target, sorted(new Operation(Root.PROJECTION, KEPT, OperationType.INSTALL_OBJECT, kept, KEPT_BYTES.length, null)), finalState(kept));
		UpdateTransaction transaction = UpdateTestFixtures.createTransaction(storage, plan, target);
		transaction.phase = UpdateTransaction.Phase.DEFERRED;
		transaction.resultStatus = UpdateTransaction.Status.valueOf(status);
		ConfigTools.writeAtomic(storage.transactionFile(), transaction);
		byte[] edit = "player edit\n".getBytes(StandardCharsets.UTF_8);
		Path overlay = storage.overlayFile(PACK, KEPT);
		Files.createDirectories(overlay.getParent());
		Files.write(overlay, edit);

		assertInstanceOf(UpdateCommit.ReplanRequired.class, UpdateTestFixtures.commits(storage).run(new UpdateCommit.Recover(null)));
		assertArrayEquals(edit, Files.readAllBytes(overlay));
	}

	private static List<Operation> sorted(Operation... operations) {
		return Arrays.stream(operations).sorted(Operation.ORDER).toList();
	}

	private static List<ProjectedFile> finalState(String kept, ProjectedFile... others) {
		ArrayList<ProjectedFile> rows = new ArrayList<>(List.of(new ProjectedFile(Root.PROJECTION, KEPT, true, kept, KEPT_BYTES.length)));
		rows.addAll(List.of(others));
		rows.sort(Comparator.comparing((ProjectedFile row) -> row.root().ordinal()).thenComparing(ProjectedFile::relativePath));
		return rows;
	}

	static void installFixture(ClientStorage storage, String name) throws Exception {
		try (InputStream fixture = OverlayTombstoneCommitTest.class.getResourceAsStream("journals/" + name)) {
			Files.createDirectories(storage.transactionFile().getParent());
			Files.write(storage.transactionFile(), fixture.readAllBytes());
		}
	}

	static String store(ClientStorage storage, byte[] bytes) throws Exception {
		String hash = HashUtils.sha1(bytes);
		ClientObjectStore.storeObject(storage, hash, bytes);
		return hash;
	}
}
