package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The transaction is the only record of which projection a plan was planned against, so ownership proofs are judged
 * against that record and not against whatever the journal happens to name when the commit runs.
 */
class RecordedInstalledLedgerTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void aPublicationStartedPendingGenerationProvesTheReplacementPlansLedgerCleanup() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		StagedReplacement replacement = stagedReplacement(storage);

		assertTrue(UpdateTestFixtures.commit(storage, replacement.plan(), replacement.target()).success());
		assertLedgerCleanupApplied(storage, replacement);
	}

	@Test
	void recoveryOfALedgerCleanupPreservationUsesTheRecordedInstalledLedger() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		StagedReplacement replacement = stagedReplacement(storage);

		// The mailbox now names the replacement, and nothing is active either, so the staged journal that proved the
		// ledger cleanup is gone: only the projection the plan recorded can still answer for it.
		ConfigTools.writeAtomic(storage.transactionFile(), replacement.transaction());
		assertNull(storage.readActiveState());

		UpdateTransactionExecutor.Execution execution = UpdateTestFixtures.executor(storage).recoverLatest();

		assertTrue(execution.success());
		assertLedgerCleanupApplied(storage, replacement);
		assertFalse(Files.exists(storage.transactionFile()));
	}

	@Test
	void aCommitAppliesAfterThePlannedProjectionLeavesTheJournal() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		StagedReplacement replacement = stagedReplacement(storage);

		// The journal the plan was planned against is gone before the commit even starts, and the answer the ledger
		// proof needs is no longer derivable from anything but the transaction itself.
		Files.delete(storage.transactionFile());
		assertNull(UpdateTransaction.read(storage.transactionFile()));

		UpdateTransactionExecutor.Execution execution = UpdateTestFixtures.executor(storage).commit(replacement.transaction(), replacement.target());

		assertTrue(execution.success());
		assertLedgerCleanupApplied(storage, replacement);
		assertEquals(replacement.target().packTarget().contentToken(), storage.readActiveState().contentToken);
	}

	/** The preserved bytes came back into the object store and their live path is gone, which is the whole point of the proof. */
	private static void assertLedgerCleanupApplied(ClientStorage storage, StagedReplacement replacement) throws Exception {
		assertFalse(Files.exists(storage.gameDirectory().resolve(replacement.preservedPath())));
		assertTrue(FileIntegrity.matches(storage.objectFile(replacement.preservedHash()), replacement.preservedSize(), replacement.preservedHash()));
	}

	/**
	 * A replacement planned against a publication that already started, the plan that proves it, and the transaction
	 * that records the projection its ledger proof names. Nothing but that record names the projection once the staged
	 * journal is gone.
	 */
	private record StagedReplacement(UpdatePlan plan, UpdateTransaction transaction, SelectedModpackTarget target, String preservedPath, String preservedHash, long preservedSize) {}

	private static StagedReplacement stagedReplacement(ClientStorage storage) throws Exception {
		byte[] stagedBytes = "staged-generation".getBytes(StandardCharsets.UTF_8);
		String stagedHash = store(storage, stagedBytes);
		String preservedPath = "config/old.json";
		SelectedModpackTarget stagedTarget = UpdateTestFixtures.target(storage, preservedPath, "config", false, stagedHash, stagedBytes.length);
		UpdateTransaction staged = UpdateTestFixtures.createTransaction(storage, UpdateTestFixtures.plan(stagedTarget, List.of(
				new Operation(Root.PROJECTION, preservedPath, OperationType.INSTALL_OBJECT, stagedHash, stagedBytes.length, null),
				new Operation(Root.GAME_DIR, preservedPath, OperationType.INSTALL_OBJECT, stagedHash, stagedBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, preservedPath, true, stagedHash, stagedBytes.length),
						new ProjectedFile(Root.GAME_DIR, preservedPath, true, stagedHash, stagedBytes.length))),
				stagedTarget);
		staged.phase = UpdateTransaction.Phase.PROJECTED;
		ConfigTools.writeAtomic(storage.transactionFile(), staged);
		Files.createDirectories(storage.gameDirectory().resolve("config"));
		Files.write(storage.gameDirectory().resolve(preservedPath), stagedBytes);

		byte[] targetBytes = "target-generation".getBytes(StandardCharsets.UTF_8);
		String targetHash = store(storage, targetBytes);
		ModpackJsons.CompleteModpackContentFields targetFields = UpdateTestFixtures.fields("config/new.json", "config", false, targetHash, targetBytes.length);
		targetFields.modpackId = "def5678";
		PackDocument targetDocument = TestPacks.document(GroupManifestValidator.validate(targetFields));
		TestPacks.stageGeneration(storage, targetDocument);
		SelectedModpackTarget target = SelectedModpackTarget.prepare(targetDocument, null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
		ModpackJsons.ModpackContentFields installed = ClientProjectionView.observe(storage).target();
		UpdatePlan replacement = UpdatePlanner.plan(new UpdatePlanner.Input(installed, target.flatTarget(),
				Map.of(new UpdatePlan.FileKey(Root.GAME_DIR, preservedPath), new UpdatePlan.FileState(stagedHash, stagedBytes.length, true)),
				Set.of(), List.of(), List.of(), List.of(), List.of(),
				new UpdatePlanner.SelectionContext(installed.modpackId, installed), new ClientConfigJsons.ClientConfigFieldsV3()));
		assertEquals(List.of(new UpdatePlan.Preservation(Root.GAME_DIR, preservedPath, stagedHash, stagedBytes.length)), replacement.preservations());
		return new StagedReplacement(replacement, UpdateTestFixtures.createTransaction(storage, replacement, target, installed.ownershipLedger), target, preservedPath, stagedHash, stagedBytes.length);
	}

	private static String store(ClientStorage storage, byte[] bytes) throws Exception {
		Path temporary = Files.createTempFile(storage.objectsDirectory(), ".object-", ".tmp");
		Files.write(temporary, bytes);
		String hash = HashUtils.getHash(temporary);
		Path destination = storage.objectFile(hash);
		Files.createDirectories(destination.getParent());
		Files.move(temporary, destination);
		return hash;
	}
}
