package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.cache.FileCache;

class UpdateTransactionExecutorTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void commitsAnImmutableProjectionFromCas() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "projection-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(storage, bytes);
		SelectedModpackTarget target = target(storage, "mods/new.jar", "mod", false, hash, bytes.length);
		Path projectionFile = storage.activePath("mods/new.jar");
		UpdatePlan plan = plan(target, clientConfig(target.manifest().modpackId()),
				List.of(new Operation(Root.PROJECTION, "mods/new.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/new.jar", true, hash, bytes.length)));

		UpdateTransactionExecutor.Execution execution = commit(storage, plan, target);

		assertTrue(execution.success());
		assertTrue(FileIntegrity.matches(projectionFile, bytes.length, hash));
		assertVerifiedObjectProjection(storage.objectFile(hash), projectionFile, bytes.length, hash);
		ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
		assertEquals(target.packTarget().contentToken(), activeState.contentToken);
		assertEquals(target.document().ownershipLedger(), OwnershipLedger.fromFields(activeState.ownershipLedger));
		assertEquals(target.selection().intent(), new ClientSelectionStore(storage.selectionFile()).get(target.manifest().modpackId()).orElseThrow());
		assertEquals(target.manifest().modpackId(), ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class).orElseThrow().selectedModpackId);
		assertFalse(Files.exists(storage.automodpackDirectory().resolve("modpacks")));
		assertFalse(Files.exists(storage.transactionFile()));
	}

	@Test
	void commitsGeneratedCopyOwnershipWithTheGenerationTransaction() throws Exception {
		ClientStorage storage = storage();
		byte[] rootBytes = "root-object".getBytes(StandardCharsets.UTF_8);
		byte[] nestedBytes = "nested-object".getBytes(StandardCharsets.UTF_8);
		String rootHash = store(storage, rootBytes);
		String nestedHash = store(storage, nestedBytes);
		SelectedModpackTarget target = target(storage, "mods/root.jar", "mod", false, rootHash, rootBytes.length);
		UpdatePlan.NestedCopy generated = new UpdatePlan.NestedCopy("mods/nested.jar", nestedHash, nestedBytes.length);
		UpdatePlan plan = new UpdatePlan(target.manifest().modpackId(), target.packTarget(), List.of(
				new Operation(Root.PROJECTION, "mods/root.jar", OperationType.INSTALL_OBJECT, rootHash, rootBytes.length, null),
				new Operation(Root.GAME_DIR, "mods/nested.jar", OperationType.INSTALL_OBJECT, nestedHash, nestedBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/root.jar", true, rootHash, rootBytes.length),
						new ProjectedFile(Root.GAME_DIR, "mods/nested.jar", true, nestedHash, nestedBytes.length)),
				clientConfig(target.manifest().modpackId()), Set.of(UpdatePlan.RestartReason.FIXED_NESTED_MODS), List.of(), List.of(), List.of(), List.of(generated), ChangeSet.empty());

		assertTrue(commit(storage, plan, target).success());

		GeneratedCopyState state = GeneratedCopyState.read(storage, target.manifest().modpackId(), target.packTarget().contentToken(),
				UpdateTransaction.digest(target.selection().intent()));
		assertEquals(List.of(new GeneratedCopyState.Entry("mods/nested.jar", nestedHash, nestedBytes.length)), state.entries());
		assertTrue(FileIntegrity.matches(storage.modsDirectory().resolve("nested.jar"), nestedBytes.length, nestedHash));
	}

	@Test
	void persistsGeneratedCopyOwnershipBeforeTheFirstLiveMutation() throws Exception {
		ClientStorage storage = storage();
		byte[] rootBytes = "root-object".getBytes(StandardCharsets.UTF_8);
		byte[] nestedBytes = "nested-object".getBytes(StandardCharsets.UTF_8);
		String rootHash = store(storage, rootBytes);
		String nestedHash = store(storage, nestedBytes);
		SelectedModpackTarget target = target(storage, "mods/root.jar", "mod", false, rootHash, rootBytes.length);
		UpdatePlan.NestedCopy generated = new UpdatePlan.NestedCopy("mods/nested.jar", nestedHash, nestedBytes.length);
		UpdatePlan plan = new UpdatePlan(target.manifest().modpackId(), target.packTarget(), List.of(
				new Operation(Root.PROJECTION, "mods/root.jar", OperationType.INSTALL_OBJECT, rootHash, rootBytes.length, null),
				new Operation(Root.GAME_DIR, "mods/nested.jar", OperationType.INSTALL_OBJECT, nestedHash, nestedBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/root.jar", true, rootHash, rootBytes.length),
						new ProjectedFile(Root.GAME_DIR, "mods/nested.jar", true, nestedHash, nestedBytes.length)),
				clientConfig(target.manifest().modpackId()), Set.of(UpdatePlan.RestartReason.FIXED_NESTED_MODS), List.of(), List.of(), List.of(), List.of(generated), ChangeSet.empty());
		Files.createDirectories(storage.modsDirectory().resolve("nested.jar"));

		UpdateTransactionExecutor.Execution execution = commit(storage, plan, target);

		assertTrue(execution.replanRequired());
		GeneratedCopyState state = GeneratedCopyState.read(storage, target.manifest().modpackId(), target.packTarget().contentToken(),
				UpdateTransaction.digest(target.selection().intent()));
		assertEquals(List.of(new GeneratedCopyState.Entry("mods/nested.jar", nestedHash, nestedBytes.length)), state.entries());
		assertTrue(Files.isDirectory(storage.modsDirectory().resolve("nested.jar")), "The live mutation must not have run");
	}

	@Test
	void transactionEntriesRoundTripThroughRuntimeGson() {
		String sourceHash = "a".repeat(HashUtils.SHA1_HEX_LENGTH);
		String targetHash = "b".repeat(HashUtils.SHA1_HEX_LENGTH);
		List<Object> values = List.of(
				new Operation(Root.PROJECTION, "mods/example.jar", OperationType.INSTALL_OBJECT, sourceHash, 12, null),
				new ProjectedFile(Root.PROJECTION, "mods/example.jar", true, sourceHash, 12),
				new UpdatePlan.Preservation(Root.GAME_DIR, "mods/local.jar", sourceHash, 12, UpdatePlan.PreservationProof.PLAYER_CONSENT),
				new UpdatePlan.BaselineCapture(Root.PROJECTION, "config/example.json", targetHash, 9, false),
				new UpdatePlan.Conflict("pack123", sourceHash, Set.of("example"), "mods/local.jar", sourceHash, 12, "mods/server.jar", targetHash, 14,
						UpdatePlan.ConflictAction.PRESERVE_LOCAL));

		for (Object value : values) assertEquals(value, ConfigTools.parse(ConfigTools.GSON.toJson(value), value.getClass()));
	}

	@Test
	void metadataOnlyUpdateKeepsProjectionAndDoesNotRequireCasObject() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "metadata-only-projection".getBytes(StandardCharsets.UTF_8);
		Files.createDirectories(storage.activePath("mods"));
		String hash = HashUtils.getHash(Files.write(storage.activePath("mods/existing.jar"), bytes));
		SelectedModpackTarget target = target(storage, "mods/existing.jar", "mod", false, hash, bytes.length);
		UpdatePlan plan = new UpdatePlan(target.manifest().modpackId(), target.packTarget(), List.of(),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/existing.jar", true, hash, bytes.length)), clientConfig(target.manifest().modpackId()), Set.of(), List.of(), List.of(), List.of(), List.of(),
				ChangeSet.empty());

		UpdateTransactionExecutor.Execution execution = commit(storage, plan, target);

		assertTrue(execution.success());
		assertTrue(FileIntegrity.matches(storage.activePath("mods/existing.jar"), bytes.length, hash));
		assertTrue(Files.exists(storage.objectFile(hash)), "the instance snapshot pins projection bytes in CAS");
		assertEquals(target.packTarget().contentToken(), storage.readActiveState().contentToken);
	}

	@Test
	void emptyOperationsRebuildsProjectionWhenAnUnlistedFileIsPresent() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "metadata-only-projection".getBytes(StandardCharsets.UTF_8);
		String hash = store(storage, bytes);
		Files.createDirectories(storage.activePath("mods"));
		Files.write(storage.activePath("mods/existing.jar"), bytes);
		Files.createDirectories(storage.activePath("shaderpacks"));
		Path stray = storage.activePath("shaderpacks/ComplementaryReimagined_r5.8.1.zip.txt");
		Files.writeString(stray, "#Mon Aug 24 12:06:20 PDT 2026\nFXAA_STRENGTH=100\n", StandardCharsets.UTF_8);
		SelectedModpackTarget target = target(storage, "mods/existing.jar", "mod", false, hash, bytes.length);
		UpdatePlan plan = new UpdatePlan(target.manifest().modpackId(), target.packTarget(), List.of(),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/existing.jar", true, hash, bytes.length)), clientConfig(target.manifest().modpackId()), Set.of(), List.of(), List.of(), List.of(), List.of(),
				ChangeSet.empty());

		UpdateTransactionExecutor.Execution execution = commit(storage, plan, target);

		assertTrue(execution.success());
		assertTrue(FileIntegrity.matches(storage.activePath("mods/existing.jar"), bytes.length, hash));
		assertFalse(Files.exists(stray));
		assertEquals(target.packTarget().contentToken(), storage.readActiveState().contentToken);
	}

	@Test
	void recoveryRebuildsAPartialProjectionSwapFromCas() throws Exception {
		ClientStorage storage = storage();
		byte[] oldBytes = "old-projection".getBytes(StandardCharsets.UTF_8);
		String oldHash = store(storage, oldBytes);
		SelectedModpackTarget oldTarget = target(storage, "mods/old.jar", "mod", false, oldHash, oldBytes.length);
		UpdateTransactionExecutor executor = executor(storage);
		assertTrue(commit(storage, plan(oldTarget, clientConfig(oldTarget.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/old.jar", OperationType.INSTALL_OBJECT, oldHash, oldBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/old.jar", true, oldHash, oldBytes.length))), oldTarget).success());

		byte[] newBytes = "new-projection".getBytes(StandardCharsets.UTF_8);
		String newHash = store(storage, newBytes);
		GroupManifest newManifest = GroupManifestValidator.validate(fields("mods/new.jar", "mod", false, newHash, newBytes.length));
		PackDocument newRecord = PackDocument.create(newManifest, TestPacks.policySha1(newManifest), Instant.parse("2026-01-02T00:00:00Z"), oldTarget.document().ownershipLedger());
		SelectionIntent intent = oldTarget.selection().intent();
		TestPacks.stageGeneration(storage, newRecord);
		SelectedModpackTarget newTarget = SelectedModpackTarget.prepare(newRecord, intent, intent, ClientPlatform.LINUX);
		UpdatePlan newPlan = plan(newTarget, clientConfig(newTarget.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/new.jar", OperationType.INSTALL_OBJECT, newHash, newBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/new.jar", true, newHash, newBytes.length)));
		UpdateTransaction transaction = createTransaction(storage, newPlan, newTarget);
		ConfigTools.writeAtomic(storage.transactionFile(), transaction);
		Files.move(storage.activeDirectory(), storage.backupDirectory());
		Files.createDirectories(storage.activeDirectory().resolve("mods"));
		Files.writeString(storage.activePath("mods/partial.jar"), "partial", StandardCharsets.UTF_8);

		assertTrue(executor.recoverLatest().success());

		assertTrue(FileIntegrity.matches(storage.activePath("mods/new.jar"), newBytes.length, newHash));
		assertFalse(Files.exists(storage.activePath("mods/partial.jar")));
		assertFalse(Files.exists(storage.backupDirectory()));
		assertFalse(Files.exists(storage.transactionFile()));
	}

	@Test
	void abandonRestoresBackupWhenFinalizeHasNotCommitted() throws Exception {
		ClientStorage storage = storage();
		byte[] oldBytes = "old-projection".getBytes(StandardCharsets.UTF_8);
		String oldHash = store(storage, oldBytes);
		SelectedModpackTarget oldTarget = target(storage, "mods/old.jar", "mod", false, oldHash, oldBytes.length);
		UpdateTransactionExecutor executor = executor(storage);
		assertTrue(commit(storage, plan(oldTarget, clientConfig(oldTarget.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/old.jar", OperationType.INSTALL_OBJECT, oldHash, oldBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/old.jar", true, oldHash, oldBytes.length))), oldTarget).success());

		byte[] newBytes = "new-projection".getBytes(StandardCharsets.UTF_8);
		String newHash = store(storage, newBytes);
		SelectedModpackTarget newTarget = nextTarget(storage, oldTarget, "mods/new.jar", newHash, newBytes.length, Instant.parse("2026-01-02T00:00:00Z"));
		UpdatePlan newPlan = plan(newTarget, clientConfig(newTarget.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/new.jar", OperationType.INSTALL_OBJECT, newHash, newBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/new.jar", true, newHash, newBytes.length)));
		UpdateTransaction transaction = createTransaction(storage, newPlan, newTarget);
		ConfigTools.writeAtomic(storage.transactionFile(), transaction);
		Files.move(storage.activeDirectory(), storage.backupDirectory());
		Files.createDirectories(storage.activeDirectory().resolve("mods"));
		Files.writeString(storage.activePath("mods/partial.jar"), "partial", StandardCharsets.UTF_8);
		Files.createDirectories(storage.incomingDirectory());
		Files.writeString(storage.incomingDirectory().resolve("stale.txt"), "stale", StandardCharsets.UTF_8);

		Path stuckJournal = executor.abandonStuckPublication(transaction);

		assertTrue(FileIntegrity.matches(storage.activePath("mods/old.jar"), oldBytes.length, oldHash));
		assertFalse(Files.exists(storage.activePath("mods/partial.jar")));
		assertFalse(Files.exists(storage.backupDirectory()));
		assertFalse(Files.exists(storage.incomingDirectory()));
		assertFalse(Files.exists(storage.transactionFile()));
		assertTrue(Files.isRegularFile(stuckJournal));
		assertEquals(oldTarget.packTarget().contentToken(), storage.readActiveState().contentToken);
	}

	@Test
	void abandonKeepsActiveWhenFinalizeAlreadyCommitted() throws Exception {
		ClientStorage storage = storage();
		byte[] oldBytes = "old-projection".getBytes(StandardCharsets.UTF_8);
		String oldHash = store(storage, oldBytes);
		SelectedModpackTarget oldTarget = target(storage, "mods/old.jar", "mod", false, oldHash, oldBytes.length);
		UpdateTransactionExecutor executor = executor(storage);
		assertTrue(commit(storage, plan(oldTarget, clientConfig(oldTarget.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/old.jar", OperationType.INSTALL_OBJECT, oldHash, oldBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/old.jar", true, oldHash, oldBytes.length))), oldTarget).success());

		byte[] newBytes = "new-projection".getBytes(StandardCharsets.UTF_8);
		String newHash = store(storage, newBytes);
		SelectedModpackTarget newTarget = nextTarget(storage, oldTarget, "mods/new.jar", newHash, newBytes.length, Instant.parse("2026-01-02T00:00:00Z"));
		UpdatePlan newPlan = plan(newTarget, clientConfig(newTarget.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/new.jar", OperationType.INSTALL_OBJECT, newHash, newBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/new.jar", true, newHash, newBytes.length)));
		UpdateTransaction transaction = createTransaction(storage, newPlan, newTarget);
		ConfigTools.writeAtomic(storage.transactionFile(), transaction);
		Files.move(storage.activeDirectory(), storage.backupDirectory());
		Files.createDirectories(storage.activePath("mods"));
		Files.write(storage.activePath("mods/new.jar"), newBytes);
		storage.writeActiveState(newTarget.manifest().modpackId(), newTarget.packTarget().contentToken(), newTarget.document().ownershipLedger().toFields());

		executor.abandonStuckPublication(transaction);

		assertTrue(FileIntegrity.matches(storage.activePath("mods/new.jar"), newBytes.length, newHash));
		assertFalse(Files.exists(storage.backupDirectory()));
		assertFalse(Files.exists(storage.transactionFile()));
		assertEquals(newTarget.packTarget().contentToken(), storage.readActiveState().contentToken);
	}

	@Test
	void gameDirectoryDriftRequestsAReplanWithoutOverwritingTheNewBytes() throws Exception {
		ClientStorage storage = storage();
		byte[] expectedBytes = "expected-game-file".getBytes(StandardCharsets.UTF_8);
		String expectedHash = store(storage, expectedBytes);
		SelectedModpackTarget target = target(storage, "config/replanned.json", "config", false, expectedHash, expectedBytes.length);
		UpdatePlan plan = plan(target, clientConfig(target.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "config/replanned.json", OperationType.INSTALL_OBJECT, expectedHash, expectedBytes.length, null),
				new Operation(Root.GAME_DIR, "config/replanned.json", OperationType.INSTALL_OBJECT, expectedHash, expectedBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "config/replanned.json", true, expectedHash, expectedBytes.length),
						new ProjectedFile(Root.GAME_DIR, "config/replanned.json", true, expectedHash, expectedBytes.length)));
		UpdateTransaction transaction = createTransaction(storage, plan, target);
		Path live = storage.gameDirectory().resolve("config/replanned.json");
		byte[] newerBytes = "newer-player-file".getBytes(StandardCharsets.UTF_8);
		Files.createDirectories(live.getParent());
		Files.write(live, newerBytes);
		ConfigTools.writeAtomic(storage.transactionFile(), transaction);

		UpdateTransactionExecutor.Execution execution = executor(storage).recoverLatest();

		assertTrue(execution.replanRequired());
		assertEquals(UpdateTransaction.Status.REPLAN_REQUIRED, execution.status());
		assertArrayEquals(newerBytes, Files.readAllBytes(live));
		assertEquals(UpdateTransaction.Phase.DEFERRED, persistedTransaction(storage).phase);
		assertEquals(UpdateTransaction.Status.REPLAN_REQUIRED, persistedTransaction(storage).resultStatus);
	}

	@Test
	void aRebuiltTransactionRetiresAnInterruptedApply() throws Exception {
		ClientStorage storage = storage();
		byte[] earlyBytes = "early-game-file".getBytes(StandardCharsets.UTF_8);
		String earlyHash = store(storage, earlyBytes);
		byte[] expectedBytes = "expected-game-file".getBytes(StandardCharsets.UTF_8);
		String expectedHash = store(storage, expectedBytes);
		SelectedModpackTarget target = twoFileTarget(storage, "config/early.txt", "config/replanned.json", earlyHash, earlyBytes.length, expectedHash, expectedBytes.length);
		Path drifted = storage.gameDirectory().resolve("config/replanned.json");
		Files.createDirectories(drifted.getParent());
		byte[] playerBytes = "newer-player-file".getBytes(StandardCharsets.UTF_8);
		Files.write(drifted, playerBytes);
		String playerHash = HashUtils.sha1(playerBytes);

		// Attempt one plans against a world without the player's file and dies on it, after the early file is already materialized.
		UpdatePlan interruptedPlan = plan(target, clientConfig(target.manifest().modpackId()), List.of(
				new Operation(Root.GAME_DIR, "config/early.txt", OperationType.INSTALL_OBJECT, earlyHash, earlyBytes.length, null),
				new Operation(Root.GAME_DIR, "config/replanned.json", OperationType.INSTALL_OBJECT, expectedHash, expectedBytes.length, null),
				new Operation(Root.PROJECTION, "config/replanned.json", OperationType.INSTALL_OBJECT, expectedHash, expectedBytes.length, null))
				.stream().sorted(Operation.ORDER).toList(),
				List.of(new ProjectedFile(Root.PROJECTION, "config/early.txt", true, earlyHash, earlyBytes.length),
						new ProjectedFile(Root.PROJECTION, "config/replanned.json", true, expectedHash, expectedBytes.length),
						new ProjectedFile(Root.GAME_DIR, "config/early.txt", true, earlyHash, earlyBytes.length),
						new ProjectedFile(Root.GAME_DIR, "config/replanned.json", true, expectedHash, expectedBytes.length)));
		UpdateTransactionExecutor.Execution first = executor(storage).commit(createTransaction(storage, interruptedPlan, target));

		assertTrue(first.replanRequired());
		assertEquals(UpdateTransaction.Status.REPLAN_REQUIRED, persistedTransaction(storage).resultStatus);
		assertArrayEquals(earlyBytes, Files.readAllBytes(storage.gameDirectory().resolve("config/early.txt")));
		assertArrayEquals(playerBytes, Files.readAllBytes(drifted));

		// The rebuild observes the same world attempt one left behind: both game ops now name the bytes they expect to find.
		UpdatePlan rebuiltPlan = plan(target, clientConfig(target.manifest().modpackId()), List.of(
				new Operation(Root.GAME_DIR, "config/early.txt", OperationType.INSTALL_OBJECT, earlyHash, earlyBytes.length, earlyHash),
				new Operation(Root.GAME_DIR, "config/replanned.json", OperationType.INSTALL_OBJECT, expectedHash, expectedBytes.length, playerHash),
				new Operation(Root.PROJECTION, "config/replanned.json", OperationType.INSTALL_OBJECT, expectedHash, expectedBytes.length, null))
				.stream().sorted(Operation.ORDER).toList(),
				List.of(new ProjectedFile(Root.PROJECTION, "config/early.txt", true, earlyHash, earlyBytes.length),
						new ProjectedFile(Root.PROJECTION, "config/replanned.json", true, expectedHash, expectedBytes.length),
						new ProjectedFile(Root.GAME_DIR, "config/early.txt", true, earlyHash, earlyBytes.length),
						new ProjectedFile(Root.GAME_DIR, "config/replanned.json", true, expectedHash, expectedBytes.length)));
		UpdateTransactionExecutor.Execution second = executor(storage).commit(createTransaction(storage, rebuiltPlan, target));

		assertTrue(second.success());
		assertArrayEquals(earlyBytes, Files.readAllBytes(storage.gameDirectory().resolve("config/early.txt")));
		assertArrayEquals(expectedBytes, Files.readAllBytes(drifted));
		assertFalse(Files.exists(storage.transactionFile()));
		assertEquals(target.packTarget().contentToken(), storage.readActiveState().contentToken);
	}

	@Test
	void clientConfigurationDriftRequestsAReplanWithoutOverwritingTheNewSettings() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "config-drift".getBytes(StandardCharsets.UTF_8);
		String hash = store(storage, bytes);
		SelectedModpackTarget target = target(storage, "mods/config-drift.jar", "mod", false, hash, bytes.length);
		UpdatePlan plan = plan(target, clientConfig(target.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/config-drift.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/config-drift.jar", true, hash, bytes.length)));
		ClientConfigJsons.ClientConfigFieldsV3 expected = new ClientConfigJsons.ClientConfigFieldsV3();
		ConfigTools.writeAtomic(storage.clientConfigFile(), expected);
		UpdateTransaction transaction = UpdateTransaction.create(plan, target, storage.overlayDigest(target.manifest().modpackId()), expected);
		ClientConfigJsons.ClientConfigFieldsV3 newer = new ClientConfigJsons.ClientConfigFieldsV3(expected);
		newer.playMusic = false;
		ConfigTools.writeAtomic(storage.clientConfigFile(), newer);

		UpdateTransactionExecutor.Execution execution = executor(storage).commit(transaction);

		assertTrue(execution.replanRequired());
		assertFalse(ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class).orElseThrow().playMusic);
		assertEquals(UpdateTransaction.Status.REPLAN_REQUIRED, persistedTransaction(storage).resultStatus);
	}

	@Test
	void replacesDeferredProjectionRequestInTheFixedMailboxWithTheLatestTarget() throws Exception {
		ClientStorage storage = storage();
		byte[] oldBytes = "mailbox-old".getBytes(StandardCharsets.UTF_8);
		String oldHash = store(storage, oldBytes);
		SelectedModpackTarget installed = target(storage, "mods/mailbox-old.jar", "mod", false, oldHash, oldBytes.length);
		assertTrue(commit(storage, plan(installed, clientConfig(installed.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/mailbox-old.jar", OperationType.INSTALL_OBJECT, oldHash, oldBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/mailbox-old.jar", true, oldHash, oldBytes.length))), installed).success());

		byte[] deferredBytes = "mailbox-deferred".getBytes(StandardCharsets.UTF_8);
		String deferredHash = store(storage, deferredBytes);
		SelectedModpackTarget deferredTarget = nextTarget(storage, installed, "mods/mailbox-deferred.jar", deferredHash, deferredBytes.length, Instant.parse("2026-01-02T00:00:00Z"));
		UpdatePlan deferredPlan = plan(deferredTarget, clientConfig(deferredTarget.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/mailbox-deferred.jar", OperationType.INSTALL_OBJECT, deferredHash, deferredBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/mailbox-deferred.jar", true, deferredHash, deferredBytes.length)));
		UpdateTransaction deferred = createTransaction(storage, deferredPlan, deferredTarget);
		deferred.phase = UpdateTransaction.Phase.DEFERRED;
		deferred.resultStatus = UpdateTransaction.Status.DEFERRED_LOCKED;
		ConfigTools.writeAtomic(storage.transactionFile(), deferred);
		Files.createDirectories(storage.activePath("shaderpacks"));
		Files.writeString(storage.activePath("shaderpacks/ComplementaryReimagined_r5.8.1.zip.txt"), "#Mon leftover\n", StandardCharsets.UTF_8);

		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			ClientProjectionView.Snapshot view = ClientProjectionView.open(storage).snapshot(cache);
			assertFalse(ClientProjectionView.publicationStarted(storage, deferred));
			assertEquals(installed.flatTarget().contentToken, view.target().contentToken);
			assertEquals(new UpdatePlan.FileState(oldHash, oldBytes.length, true), view.files().get("mods/mailbox-old.jar"));
			assertNull(view.files().get("mods/mailbox-deferred.jar"));
			assertNotNull(view.files().get("shaderpacks/ComplementaryReimagined_r5.8.1.zip.txt"));
			assertTrue(view.sourceCandidates("mods/mailbox-deferred.jar").contains(storage.objectFile(deferredHash)));
		}
		assertTrue(FileIntegrity.matches(storage.activePath("mods/mailbox-old.jar"), oldBytes.length, oldHash));
		Files.createDirectories(storage.incomingDirectory());
		Files.writeString(storage.incomingDirectory().resolve("stale.txt"), "stale", StandardCharsets.UTF_8);

		byte[] latestBytes = "mailbox-latest".getBytes(StandardCharsets.UTF_8);
		String latestHash = store(storage, latestBytes);
		SelectedModpackTarget latestTarget = nextTarget(storage, deferredTarget, "mods/mailbox-latest.jar", latestHash, latestBytes.length, Instant.parse("2026-01-03T00:00:00Z"));
		UpdatePlan latestPlan = plan(latestTarget, clientConfig(latestTarget.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/mailbox-latest.jar", OperationType.INSTALL_OBJECT, latestHash, latestBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/mailbox-latest.jar", true, latestHash, latestBytes.length)));

		assertTrue(commit(storage, latestPlan, latestTarget).success());
		assertTrue(FileIntegrity.matches(storage.activePath("mods/mailbox-latest.jar"), latestBytes.length, latestHash));
		assertFalse(Files.exists(storage.activePath("mods/mailbox-deferred.jar")));
		assertFalse(Files.exists(storage.incomingDirectory().resolve("stale.txt")));
		assertFalse(Files.exists(storage.incomingDirectory()));
		assertFalse(Files.exists(storage.backupDirectory()));
		assertFalse(Files.exists(storage.transactionFile()));
	}

	@Test
	void usesPendingPlannedSelectionAsTheLogicalConfigurationBase() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "pending-selection".getBytes(StandardCharsets.UTF_8);
		String hash = store(storage, bytes);
		ModpackJsons.CompleteModpackContentFields fields = fields("mods/pending-selection.jar", "mod", false, hash, bytes.length);
		fields.modpackId = "def5678";
		PackDocument record = TestPacks.document(GroupManifestValidator.validate(fields));
		TestPacks.stageGeneration(storage, record);
		SelectedModpackTarget target = SelectedModpackTarget.prepare(record, null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
		UpdatePlan plan = plan(target, clientConfig(target.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/pending-selection.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/pending-selection.jar", true, hash, bytes.length)));
		UpdateTransaction transaction = createTransaction(storage, plan, target);
		transaction.plan().plannedClientConfig().syncLoaderVersion = false;
		ClientConfigJsons.ClientConfigFieldsV3 current = new ClientConfigJsons.ClientConfigFieldsV3();
		transaction.expectedClientConfig = new ClientConfigJsons.ClientConfigFieldsV3(current);
		current.playMusic = false;
		ConfigTools.writeAtomic(storage.clientConfigFile(), current);
		ConfigTools.writeAtomic(storage.transactionFile(), transaction);

		assertEquals(target.manifest().modpackId(), ClientProjectionView.open(storage).logicalConfig(current).selectedModpackId);
		assertFalse(ClientProjectionView.open(storage).logicalConfig(current).syncLoaderVersion);
		assertFalse(ClientProjectionView.open(storage).logicalConfig(current).playMusic);
	}

	@Test
	void firstInstallPreservesLocalSameIdModBeforeProjectionApply() throws Exception {
		ClientStorage storage = storage();
		byte[] serverBytes = "server-sodium".getBytes(StandardCharsets.UTF_8);
		String serverHash = store(storage, serverBytes);
		Path local = storage.modsDirectory().resolve("local-sodium.jar");
		byte[] localBytes = "local-sodium".getBytes(StandardCharsets.UTF_8);
		Files.write(local, localBytes);
		String localHash = HashUtils.getHash(local);
		SelectedModpackTarget target = target(storage, "mods/server-sodium.jar", "mod", false, serverHash, serverBytes.length);
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = Map.of(new UpdatePlan.FileKey(Root.GAME_DIR, "mods/local-sodium.jar"),
				new UpdatePlan.FileState(localHash, localBytes.length, true));
		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target.flatTarget(), files, Set.of(),
				List.of(new UpdatePlan.ModInfo("mods/server-sodium.jar", serverHash, serverBytes.length, "1.0.0", Set.of("sodium"), Set.of())),
				List.of(new UpdatePlan.ModInfo("mods/local-sodium.jar", localHash, localBytes.length, "1.0.0", Set.of("sodium"), Set.of())), List.of(), List.of(), null,
				clientConfig(target.manifest().modpackId())));
		UpdateTransactionExecutor.Execution execution = commit(storage, plan, target);

		assertTrue(execution.success());
		assertFalse(Files.exists(local));
		assertTrue(FileIntegrity.matches(storage.activePath("mods/server-sodium.jar"), serverBytes.length, serverHash));
		assertTrue(FileIntegrity.matches(storage.objectFile(localHash), localBytes.length, localHash));
	}

	@Test
	void firstInstallConsentPreservesLocalBytesBeforeSamePathReplacement() throws Exception {
		ClientStorage storage = storage();
		byte[] serverBytes = "server-replacement".getBytes(StandardCharsets.UTF_8);
		String serverHash = store(storage, serverBytes);
		Path local = storage.modsDirectory().resolve("shared.jar");
		byte[] localBytes = "locally-modified".getBytes(StandardCharsets.UTF_8);
		Files.write(local, localBytes);
		String localHash = HashUtils.getHash(local);
		SelectedModpackTarget target = target(storage, "mods/shared.jar", "other", false, serverHash, serverBytes.length);
		UpdatePlan.FileState localState = new UpdatePlan.FileState(localHash, localBytes.length, true);
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = Map.of(new UpdatePlan.FileKey(Root.GAME_DIR, "mods/shared.jar"), localState);
		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target.flatTarget(), files, Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				clientConfig(target.manifest().modpackId()), Map.of("mods/shared.jar", localState)));

		assertTrue(commit(storage, plan, target).success());

		assertTrue(FileIntegrity.matches(local, serverBytes.length, serverHash));
		assertTrue(FileIntegrity.matches(storage.objectFile(localHash), localBytes.length, localHash));
	}

	@Test
	void aPlannerRebuiltPlanAfterADeletePrefixStaysOutcomeCompatible() throws Exception {
		ClientStorage storage = storage();
		byte[] keepBytes = "kept-pack-file".getBytes(StandardCharsets.UTF_8);
		String keepHash = store(storage, keepBytes);
		byte[] goneBytes = "removed-pack-file".getBytes(StandardCharsets.UTF_8);
		String goneHash = store(storage, goneBytes);
		SelectedModpackTarget installed = twoFileTarget(storage, "mods/keep.jar", "mods/gone.jar", keepHash, keepBytes.length, goneHash, goneBytes.length);
		UpdatePlan installedPlan = plan(installed, clientConfig(installed.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/gone.jar", OperationType.INSTALL_OBJECT, goneHash, goneBytes.length, null),
				new Operation(Root.PROJECTION, "mods/keep.jar", OperationType.INSTALL_OBJECT, keepHash, keepBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/gone.jar", true, goneHash, goneBytes.length),
						new ProjectedFile(Root.PROJECTION, "mods/keep.jar", true, keepHash, keepBytes.length)));
		assertTrue(commit(storage, installedPlan, installed).success());

		// The next generation no longer ships gone.jar; the planner plans its removal from the projection.
		SelectedModpackTarget next = nextTarget(storage, installed, "mods/keep.jar", keepHash, keepBytes.length, Instant.now());
		UpdatePlan.FileState keepState = new UpdatePlan.FileState(keepHash, keepBytes.length, true);
		UpdatePlan.FileState goneState = new UpdatePlan.FileState(goneHash, goneBytes.length, true);
		UpdatePlan first = UpdatePlanner.plan(new UpdatePlanner.Input(installed.flatTarget(), next.flatTarget(),
				Map.of(new UpdatePlan.FileKey(Root.PROJECTION, "mods/keep.jar"), keepState, new UpdatePlan.FileKey(Root.PROJECTION, "mods/gone.jar"), goneState),
				Set.of(), List.of(), List.of(), List.of(), List.of(), null, clientConfig(next.manifest().modpackId()), null));
		assertTrue(first.operations().stream().anyMatch(operation -> operation.operation() == OperationType.DELETE && operation.relativePath().equals("mods/gone.jar")),
				"The plan must remove the file the next generation dropped");

		// The delete prefix applies for real; the rebuilt plan then observes a world where it is already done.
		Files.delete(storage.activePath("mods/gone.jar"));
		UpdatePlan rebuilt = UpdatePlanner.plan(new UpdatePlanner.Input(installed.flatTarget(), next.flatTarget(),
				Map.of(new UpdatePlan.FileKey(Root.PROJECTION, "mods/keep.jar"), keepState),
				Set.of(), List.of(), List.of(), List.of(), List.of(), null, clientConfig(next.manifest().modpackId()), null));

		assertTrue(ReviewedUpdatePlan.outcomeCompatible(first, rebuilt), "An applied delete prefix must not read as a changed review");
		ReviewedUpdatePlan.pending(first).requireCompatible(rebuilt);
	}

	@Test
	void aPlannerRebuiltPlanAfterDeletingAnUnlistedProjectionStaysOutcomeCompatible() throws Exception {
		ClientStorage storage = storage();
		byte[] keepBytes = "kept-pack-file".getBytes(StandardCharsets.UTF_8);
		String keepHash = store(storage, keepBytes);
		byte[] goneBytes = "unlisted-projection".getBytes(StandardCharsets.UTF_8);
		String goneHash = store(storage, goneBytes);
		SelectedModpackTarget installed = target(storage, "mods/keep.jar", "mod", false, keepHash, keepBytes.length);
		UpdatePlan installedPlan = plan(installed, clientConfig(installed.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/keep.jar", OperationType.INSTALL_OBJECT, keepHash, keepBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/keep.jar", true, keepHash, keepBytes.length)));
		assertTrue(commit(storage, installedPlan, installed).success());

		// gone.jar is extra projection: not on the installed or next manifest, so only the first plan carries its absent row.
		Files.write(storage.activePath("mods/gone.jar"), goneBytes);
		UpdatePlan.FileState keepState = new UpdatePlan.FileState(keepHash, keepBytes.length, true);
		UpdatePlan.FileState goneState = new UpdatePlan.FileState(goneHash, goneBytes.length, true);
		UpdatePlan first = UpdatePlanner.plan(new UpdatePlanner.Input(installed.flatTarget(), installed.flatTarget(),
				Map.of(new UpdatePlan.FileKey(Root.PROJECTION, "mods/keep.jar"), keepState, new UpdatePlan.FileKey(Root.PROJECTION, "mods/gone.jar"), goneState),
				Set.of(), List.of(), List.of(), List.of(), List.of(), null, clientConfig(installed.manifest().modpackId()), null));
		assertTrue(first.operations().stream().anyMatch(operation -> operation.operation() == OperationType.DELETE && operation.relativePath().equals("mods/gone.jar")),
				"The plan must remove the extra projection the target never listed");

		Files.delete(storage.activePath("mods/gone.jar"));
		UpdatePlan rebuilt = UpdatePlanner.plan(new UpdatePlanner.Input(installed.flatTarget(), installed.flatTarget(),
				Map.of(new UpdatePlan.FileKey(Root.PROJECTION, "mods/keep.jar"), keepState),
				Set.of(), List.of(), List.of(), List.of(), List.of(), null, clientConfig(installed.manifest().modpackId()), null));

		assertNotEquals(first.projectedFinalState(), rebuilt.projectedFinalState(), "The extra delete leaves an absent row only on the first plan");
		assertTrue(ReviewedUpdatePlan.outcomeCompatible(first, rebuilt), "destination() must ignore the already-applied extra delete");
		ReviewedUpdatePlan.pending(first).requireCompatible(rebuilt);
	}

	@Test
	void corruptPendingTransactionIsSetAsideAndDoesNotBlockANewPlan() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "blocked-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(storage, bytes);
		SelectedModpackTarget target = target(storage, "mods/blocked.jar", "mod", false, hash, bytes.length);
		UpdatePlan plan = plan(target, clientConfig(target.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/blocked.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/blocked.jar", true, hash, bytes.length)));
		Files.writeString(storage.transactionFile(), "active", StandardCharsets.UTF_8);

		// An unparseable pending transaction is evidence, not a blocker: it is set aside and the fresh plan proceeds.
		assertTrue(commit(storage, plan, target).success());
		try (var leftovers = Files.list(storage.transactionFile().getParent())) {
			assertTrue(leftovers.anyMatch(path -> path.getFileName().toString().startsWith("update-transaction.json.corrupt-")));
		}
	}

	@Test
	void failedPreflightLeavesNoDurableTransaction() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "missing-object".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(bytes);
		SelectedModpackTarget target = target(storage, "mods/missing.jar", "mod", false, hash, bytes.length);
		UpdatePlan plan = plan(target, clientConfig(target.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/missing.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/missing.jar", true, hash, bytes.length)));

		assertThrows(IOException.class, () -> commit(storage, plan, target));

		assertFalse(Files.exists(storage.transactionFile()));
	}

	@Test
	void installsEditableOverlayAsMutableCopyAndCopiesItToTheLiveGamePath() throws Exception {
		ClientStorage storage = storage();
		byte[] baseBytes = "server-base".getBytes(StandardCharsets.UTF_8);
		String baseHash = store(storage, baseBytes);
		byte[] editedBytes = "player-edit".getBytes(StandardCharsets.UTF_8);
		String editedHash = store(storage, editedBytes);
		SelectedModpackTarget target = target(storage, "config/settings.json", "config", true, baseHash, baseBytes.length);
		List<Operation> operations = List.of(
				new Operation(Root.PROJECTION, "config/settings.json", OperationType.INSTALL_OBJECT, baseHash, baseBytes.length, null),
				new Operation(Root.OVERLAY, "config/settings.json", OperationType.INSTALL_OBJECT, editedHash, editedBytes.length, null),
				new Operation(Root.GAME_DIR, "config/settings.json", OperationType.INSTALL_OBJECT, editedHash, editedBytes.length, null));
		List<ProjectedFile> finalState = List.of(
				new ProjectedFile(Root.PROJECTION, "config/settings.json", true, baseHash, baseBytes.length),
				new ProjectedFile(Root.OVERLAY, "config/settings.json", true, editedHash, editedBytes.length),
				new ProjectedFile(Root.GAME_DIR, "config/settings.json", true, editedHash, editedBytes.length));

		UpdateTransactionExecutor.Execution execution = commit(storage, plan(target, clientConfig(target.manifest().modpackId()), operations, finalState), target);

		Path overlay = storage.overlayFile(target.manifest().modpackId(), "config/settings.json");
		Path live = storage.gameDirectory().resolve("config/settings.json");
		assertTrue(execution.success());
		assertTrue(FileIntegrity.matches(overlay, editedBytes.length, editedHash));
		assertFalse(Files.isSameFile(storage.objectFile(editedHash), live));
		assertArrayEquals(editedBytes, Files.readAllBytes(live));
		assertTrue(FileIntegrity.matches(storage.activePath("config/settings.json"), baseBytes.length, baseHash));
	}

	@Test
	void preservesOwnedBytesWhileRestoringThePrePackBaseline() throws Exception {
		ClientStorage storage = storage();
		byte[] serverBytes = "pack-a-value".getBytes(StandardCharsets.UTF_8);
		byte[] baselineBytes = "player-value".getBytes(StandardCharsets.UTF_8);
		byte[] targetBytes = "pack-b-value".getBytes(StandardCharsets.UTF_8);
		String serverHash = store(storage, serverBytes);
		String baselineHash = store(storage, baselineBytes);
		String targetHash = store(storage, targetBytes);
		String restoredPath = "config/pack-a.json";
		SelectedModpackTarget installed = target(storage, restoredPath, "config", false, serverHash, serverBytes.length);
		UpdatePlan installedPlan = plan(installed, clientConfig(installed.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, restoredPath, OperationType.INSTALL_OBJECT, serverHash, serverBytes.length, null),
				new Operation(Root.GAME_DIR, restoredPath, OperationType.INSTALL_OBJECT, serverHash, serverBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, restoredPath, true, serverHash, serverBytes.length),
						new ProjectedFile(Root.GAME_DIR, restoredPath, true, serverHash, serverBytes.length)));
		assertTrue(commit(storage, installedPlan, installed).success());
		Files.delete(storage.objectFile(serverHash));

		Map<String, InstanceTree.TrackedFile> baseline = Map.of(restoredPath, new InstanceTree.TrackedFile(Root.GAME_DIR, "", restoredPath, baselineHash, baselineBytes.length));
		ModpackJsons.CompleteModpackContentFields targetFields = fields("config/pack-b.json", "config", false, targetHash, targetBytes.length);
		targetFields.modpackId = "def5678";
		PackDocument switchedDocument = TestPacks.document(GroupManifestValidator.validate(targetFields));
		TestPacks.stageGeneration(storage, switchedDocument);
		SelectedModpackTarget target = SelectedModpackTarget.prepare(switchedDocument, null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = Map.of(
				new UpdatePlan.FileKey(Root.PROJECTION, restoredPath), new UpdatePlan.FileState(serverHash, serverBytes.length, true),
				new UpdatePlan.FileKey(Root.GAME_DIR, restoredPath), new UpdatePlan.FileState(serverHash, serverBytes.length, true));
		UpdatePlanner.SelectionContext selection = new UpdatePlanner.SelectionContext(installed.manifest().modpackId(), installed.flatTarget(), Map.of(), baseline,
				Set.of(baselineHash));
		UpdatePlan switchPlan = UpdatePlanner.plan(new UpdatePlanner.Input(installed.flatTarget(), target.flatTarget(), files, Set.of(), List.of(), List.of(),
				List.of(), List.of(), selection, clientConfig(target.manifest().modpackId())));

		assertEquals(List.of(new UpdatePlan.Preservation(Root.GAME_DIR, restoredPath, serverHash, serverBytes.length)), switchPlan.preservations());
		assertTrue(switchPlan.projectedFinalState().stream().anyMatch(file -> file.root() == Root.GAME_DIR && file.relativePath().equals(restoredPath)
				&& file.present() && baselineHash.equals(file.expectedHash())));
		assertTrue(commit(storage, switchPlan, target).success());
		assertArrayEquals(baselineBytes, Files.readAllBytes(storage.gameDirectory().resolve(restoredPath)));
		assertTrue(FileIntegrity.matches(storage.objectFile(serverHash), serverBytes.length, serverHash));
	}

	@Test
	void removalSwapsToAnEmptyProjectionAndKeepsTheMirrorHistory() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "removable-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(storage, bytes);
		SelectedModpackTarget target = target(storage, "mods/remove.jar", "mod", false, hash, bytes.length);
		UpdatePlan install = plan(target, clientConfig(target.manifest().modpackId()),
				List.of(new Operation(Root.PROJECTION, "mods/remove.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/remove.jar", true, hash, bytes.length)));
		UpdateTransactionExecutor executor = executor(storage);
		commit(storage, install, target);
		Path live = storage.gameDirectory().resolve("mods/remove.jar");
		Files.write(live, bytes);
		Path generatedLive = storage.gameDirectory().resolve("mods/generated-remove.jar");
		byte[] generatedBytes = "generated-removable-object".getBytes(StandardCharsets.UTF_8);
		Files.write(generatedLive, generatedBytes);
		String generatedHash = HashUtils.getHash(generatedLive);
		SelectionIntent expected = target.selection().intent();
		GeneratedCopyState generatedCopies = new GeneratedCopyState(target.manifest().modpackId(), target.packTarget().contentToken(),
				UpdateTransaction.digest(expected), List.of(new GeneratedCopyState.Entry("mods/generated-remove.jar", generatedHash, generatedBytes.length)));
		generatedCopies.write(storage);

		Map<String, InstanceTree.TrackedFile> baseline = Map.of();
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = Map.of(
				new UpdatePlan.FileKey(Root.PROJECTION, "mods/remove.jar"), new UpdatePlan.FileState(hash, bytes.length, true),
				new UpdatePlan.FileKey(Root.GAME_DIR, "mods/remove.jar"), new UpdatePlan.FileState(hash, bytes.length, true),
				new UpdatePlan.FileKey(Root.GAME_DIR, "mods/generated-remove.jar"), new UpdatePlan.FileState(generatedHash, generatedBytes.length, true));
		ClientConfigJsons.ClientConfigFieldsV3 removalConfig = new ClientConfigJsons.ClientConfigFieldsV3();
		UpdatePlan removal = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(target.flatTarget(), baseline, files, Set.of(), generatedCopies, removalConfig));
		assertEquals(List.of(new UpdatePlan.Preservation(Root.GAME_DIR, "mods/remove.jar", hash, bytes.length)), removal.preservations());
		assertTrue(removal.operations().stream().anyMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("mods/generated-remove.jar")
				&& operation.operation() == OperationType.DELETE && generatedHash.equals(operation.expectedExistingHash())));
		UpdateTransaction transaction = UpdateTransaction.createRemoval(removal, ClientPlatform.LINUX, expected, target.flatTarget().ownershipLedger,
				storage.overlayDigest(target.manifest().modpackId()), clientConfig(target.manifest().modpackId()));
		Files.delete(storage.objectFile(hash));

		assertTrue(executor.commit(transaction).success());
		assertFalse(Files.exists(live));
		assertFalse(Files.exists(generatedLive));
		assertFalse(Files.exists(storage.generatedCopiesFile(target.manifest().modpackId(), target.packTarget().contentToken(), UpdateTransaction.digest(expected))));
		assertTrue(FileIntegrity.matches(storage.objectFile(hash), bytes.length, hash));
		assertTrue(Files.isDirectory(storage.activeDirectory()));
		try (var paths = Files.list(storage.activeDirectory())) {
			assertEquals(List.of(), paths.toList());
		}
		assertNull(storage.readActiveState());
		assertTrue(Files.exists(storage.historyJournalFile(target.manifest().modpackId())), "Removal keeps the pack's mirror history; only the explicit forget flow deletes it");
		assertTrue(new ClientSelectionStore(storage.selectionFile()).get(target.manifest().modpackId()).isEmpty());
	}

	@Test
	void deactivationRestoresTheClientAndKeepsPackState() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "deactivated-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(storage, bytes);
		String managedPath = "config/empty/deactivate.txt";
		SelectedModpackTarget target = target(storage, managedPath, "config", false, hash, bytes.length);
		UpdateTransactionExecutor executor = executor(storage);
		commit(storage, plan(target, clientConfig(target.manifest().modpackId()),
				List.of(new Operation(Root.PROJECTION, managedPath, OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, managedPath, true, hash, bytes.length))), target);
		Path live = storage.gameDirectory().resolve(managedPath);
		Files.createDirectories(live.getParent());
		Files.write(live, bytes);
		Path userFile = Files.writeString(storage.gameDirectory().resolve("config/user.txt"), "user-owned", StandardCharsets.UTF_8);
		Path overlay = storage.overlayFile(target.manifest().modpackId(), "config/options.txt");
		Files.createDirectories(overlay.getParent());
		Files.writeString(overlay, "player-edit", StandardCharsets.UTF_8);
		Map<String, InstanceTree.TrackedFile> baseline = Map.of();
		SelectionIntent expected = target.selection().intent();
		GeneratedCopyState generatedCopies = new GeneratedCopyState(target.manifest().modpackId(), target.packTarget().contentToken(),
				UpdateTransaction.digest(expected), List.of());
		generatedCopies.write(storage);
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = Map.of(
				new UpdatePlan.FileKey(Root.PROJECTION, managedPath), new UpdatePlan.FileState(hash, bytes.length, true),
				new UpdatePlan.FileKey(Root.GAME_DIR, managedPath), new UpdatePlan.FileState(hash, bytes.length, true));
		UpdatePlan deactivation = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(target.flatTarget(), baseline, files, Set.of(), generatedCopies,
				new ClientConfigJsons.ClientConfigFieldsV3()));

		assertTrue(executor.commit(UpdateTransaction.createDeactivation(deactivation, ClientPlatform.LINUX, expected, target.flatTarget().ownershipLedger,
				storage.overlayDigest(target.manifest().modpackId()), clientConfig(target.manifest().modpackId()))).success());

		assertFalse(Files.exists(live));
		assertFalse(Files.exists(live.getParent()));
		assertTrue(Files.isDirectory(userFile.getParent()));
		assertEquals("user-owned", Files.readString(userFile, StandardCharsets.UTF_8));
		assertNull(storage.readActiveState());
		assertTrue(Files.exists(storage.historyJournalFile(target.manifest().modpackId())), "Deactivation keeps the pack's mirror history");
		assertEquals(expected, new ClientSelectionStore(storage.selectionFile()).get(target.manifest().modpackId()).orElseThrow());
		assertTrue(Files.exists(storage.generatedCopiesFile(target.manifest().modpackId(), target.packTarget().contentToken(), UpdateTransaction.digest(expected))));
		assertEquals("player-edit", Files.readString(overlay, StandardCharsets.UTF_8));
	}

	@Test
	void treatsAccessDeniedAsARecoverableStorageLockOnlyWhereItMeansOne() {
		// On Windows a denied delete is how an open handle reports a sharing violation; on other kernels it is a
		// plain permission problem - a permanent failure, not a lock the update should defer behind.
		assertTrue(UpdateTransactionExecutor.isLockFailure(new AccessDeniedException("active", "backup", null), true));
		assertFalse(UpdateTransactionExecutor.isLockFailure(new AccessDeniedException("active", "backup", null), false));
		assertTrue(UpdateTransactionExecutor.isLockFailure(new FileSystemException("active", "backup", "being used by another process"), false));
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private static UpdateTransactionExecutor executor(ClientStorage storage) {
		return new UpdateTransactionExecutor(new UpdateTransactionExecutor.Context(storage, null));
	}

	private static UpdateTransactionExecutor.Execution commit(ClientStorage storage, UpdatePlan plan, SelectedModpackTarget target) throws IOException {
		ClientConfigJsons.ClientConfigFieldsV3 expected = ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
				.orElseGet(ClientConfigJsons.ClientConfigFieldsV3::new);
		return executor(storage).commit(plan, target, storage.overlayDigest(target.manifest().modpackId()), expected);
	}

	private static UpdateTransaction persistedTransaction(ClientStorage storage) {
		return ConfigTools.read(storage.transactionFile(), UpdateTransaction.class).orElseThrow();
	}

	private static String store(ClientStorage storage, byte[] bytes) throws Exception {
		Path temporary = Files.createTempFile(storage.objectsDirectory(), ".object-", ".tmp");
		Files.write(temporary, bytes);
		String hash = HashUtils.getHash(temporary);
		Path destination = storage.objectFile(hash);
		Files.createDirectories(destination.getParent());
		if (Files.exists(destination)) {
			assertTrue(FileIntegrity.matches(destination, bytes.length, hash));
			Files.delete(temporary);
		} else Files.move(temporary, destination);
		return hash;
	}

	private static void assertVerifiedObjectProjection(Path object, Path projection, long size, String hash) throws Exception {
		assertTrue(FileIntegrity.matches(projection, size, hash));
		if (Files.getFileStore(object).equals(Files.getFileStore(projection))) {
			assertTrue(Files.isSameFile(object, projection));
			assertTrue(ImmutableFiles.isProtected(object));
			assertTrue(ImmutableFiles.isProtected(projection));
		}
	}

	private static UpdatePlan plan(SelectedModpackTarget target, ClientConfigJsons.ClientConfigFieldsV3 config, List<Operation> operations, List<ProjectedFile> finalState) {
		return new UpdatePlan(target.manifest().modpackId(), target.packTarget(), operations, finalState, config, Set.of(UpdatePlan.RestartReason.SELECTED_MODPACK), List.of(), List.of(), List.of(), List.of(),
				ChangeSet.empty());
	}

	private static SelectedModpackTarget target(ClientStorage storage, String path, String type, boolean editable, String hash, long size) throws IOException {
		PackDocument document = TestPacks.document(GroupManifestValidator.validate(fields(path, type, editable, hash, size)));
		TestPacks.stageGeneration(storage, document);
		return SelectedModpackTarget.prepare(document, null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
	}

	private static SelectedModpackTarget twoFileTarget(ClientStorage storage, String pathA, String pathB, String hashA, long sizeA, String hashB, long sizeB) throws IOException {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.modpackName = "Test";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = true;
		group.files = Map.of(pathA, file(pathA, hashA, sizeA), pathB, file(pathB, hashB, sizeB));
		fields.categories = Map.of("General", Map.of("main", group));
		PackDocument document = TestPacks.document(GroupManifestValidator.validate(fields));
		TestPacks.stageGeneration(storage, document);
		return SelectedModpackTarget.prepare(document, null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
	}

	private static ModpackJsons.CompleteModpackContentFields.GroupFileFields file(String path, String hash, long size) {
		ModpackJsons.CompleteModpackContentFields.GroupFileFields file = new ModpackJsons.CompleteModpackContentFields.GroupFileFields();
		file.size = String.valueOf(size);
		file.type = path.endsWith(".jar") ? "mod" : "config";
		file.editable = false;
		file.sha1 = hash;
		file.murmur = "0";
		return file;
	}

	private static SelectedModpackTarget nextTarget(ClientStorage storage, SelectedModpackTarget parent, String path, String hash, long size, Instant createdAt) throws IOException {
		GroupManifest manifest = GroupManifestValidator.validate(fields(path, "mod", false, hash, size));
		PackDocument document = PackDocument.create(manifest, TestPacks.policySha1(manifest), createdAt, parent.document().ownershipLedger());
		TestPacks.stageGeneration(storage, document);
		return SelectedModpackTarget.prepare(document, parent.selection().intent(), parent.selection().intent(), ClientPlatform.LINUX);
	}

	private static ModpackJsons.CompleteModpackContentFields fields(String path, String type, boolean editable, String hash, long size) {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.modpackName = "Test";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = true;
		ModpackJsons.CompleteModpackContentFields.GroupFileFields file = new ModpackJsons.CompleteModpackContentFields.GroupFileFields();
		file.size = String.valueOf(size);
		file.type = type;
		file.editable = editable;
		file.sha1 = hash;
		file.murmur = "0";
		group.files = Map.of(path, file);
		fields.categories = Map.of("General", Map.of("main", group));
		return fields;
	}

	private static ClientConfigJsons.ClientConfigFieldsV3 clientConfig(String modpackId) {
		ClientConfigJsons.ClientConfigFieldsV3 config = new ClientConfigJsons.ClientConfigFieldsV3();
		config.selectedModpackId = modpackId;
		return config;
	}

	private static UpdateTransaction createTransaction(ClientStorage storage, UpdatePlan plan, SelectedModpackTarget target) throws IOException {
		ClientConfigJsons.ClientConfigFieldsV3 expected = ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
				.orElseGet(ClientConfigJsons.ClientConfigFieldsV3::new);
		return UpdateTransaction.create(plan, target, storage.overlayDigest(target.manifest().modpackId()), expected);
	}
}
