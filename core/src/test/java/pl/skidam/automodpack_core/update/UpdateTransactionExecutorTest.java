package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.update.UpdatePlan.*;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ModpackContentTools;

class UpdateTransactionExecutorTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void commitsFromCasAndPublishesManifestLastState() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		byte[] bytes = "target-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(paths, bytes);
		Files.createDirectories(paths.game());
		Path oldFile = Files.writeString(paths.game().resolve("old.txt"), "old", StandardCharsets.UTF_8);
		String oldHash = HashUtils.getHash(oldFile);

		Jsons.ModpackContentFields manifest = manifest(hash, bytes.length);
		SelectedModpackTarget target = selectedTarget(manifest);
		UpdatePlan plan = new UpdatePlan(manifest.modpackId, target.generationTarget(),
				List.of(new Operation(Root.MODPACK_DIR, "mods/new.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null),
						new Operation(Root.GAME_DIR, "old.txt", OperationType.DELETE, null, -1, oldHash)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, hash, bytes.length),
						new ProjectedFile(Root.GAME_DIR, "old.txt", false, null, -1)),
				clientConfig(manifest.modpackId), Set.of(RestartReason.REMOVED_NON_MODPACK_FILES));

		UpdateTransactionExecutor.Execution result = executor(paths, transaction -> {
			assertFalse(Files.exists(paths.catalogue()));
			assertFalse(Files.exists(paths.manifest()));
		}).commit(plan, target);

		assertTrue(result.success());
		assertFalse(Files.exists(paths.transaction()));
		assertFalse(Files.exists(oldFile));
		assertArrayEquals(bytes, Files.readAllBytes(paths.modpack().resolve("mods/new.jar")));
		Jsons.ModpackContentFields installed = ModpackContentTools.read(paths.manifest());
		assertEquals(manifest.modpackId, installed.modpackId);
		assertEquals(target.generationTarget().targetGenerationId(), installed.targetGenerationId);
		assertEquals(target.generationTarget().parentGenerationId(), installed.parentGenerationId);
		assertEquals(target.generationTarget().stateDigest(), installed.stateDigest);
		assertEquals(manifest.modpackId, ModpackContentTools.readGenerationRecord(paths.catalogue()).manifest().modpackId());
		assertEquals(target.generationRecord(), ModpackContentTools.readGenerationRecord(paths.catalogue()));
		assertEquals(Set.of("main"), new ClientSelectionStore(paths.selection()).get(manifest.modpackId).orElseThrow().requestedGroups());
		assertEquals(manifest.modpackId, ConfigTools.read(paths.clientConfig(), Jsons.ClientConfigFieldsV3.class).orElseThrow().selectedModpackId);
	}

	@Test
	void preservesLedgerOwnedFileBeforeDeletion() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		Files.createDirectories(paths.game().resolve("config"));
		byte[] oldBytes = "old-managed-file".getBytes(StandardCharsets.UTF_8);
		Path oldFile = Files.write(paths.game().resolve("config/old.txt"), oldBytes);
		String oldHash = HashUtils.getHash(oldFile);
		byte[] targetBytes = "target-object".getBytes(StandardCharsets.UTF_8);
		String targetHash = store(paths, targetBytes);

		Jsons.CompleteModpackContentFields oldFields = new Jsons.CompleteModpackContentFields();
		oldFields.modpackId = "abc1234";
		oldFields.selectionTags = Map.of();
		var oldGroup = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		oldGroup.files = Map.of("config/old.txt", new Jsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(oldBytes.length), "config", false, false, false,
				oldHash, null));
		oldFields.groups = Map.of("main", oldGroup);
		GenerationRecord oldRecord = GenerationRecord.create(GroupManifestValidator.validate(oldFields), null, Instant.parse("2026-01-01T00:00:00Z"), "");

		Jsons.CompleteModpackContentFields targetFields = new Jsons.CompleteModpackContentFields();
		targetFields.modpackId = "abc1234";
		targetFields.selectionTags = Map.of();
		var targetGroup = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		targetGroup.files = Map.of("mods/new.jar", new Jsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(targetBytes.length), "mod", false, false, false,
				targetHash, null));
		targetFields.groups = Map.of("main", targetGroup);
		GenerationRecord targetRecord = GenerationRecord.create(GroupManifestValidator.validate(targetFields), oldRecord, Instant.parse("2026-01-02T00:00:00Z"), "");
		SelectedModpackTarget target = SelectedModpackTarget.prepare(targetRecord.toFields(), null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);

		UpdatePlan plan = new UpdatePlan(target.manifest().modpackId(), target.generationTarget(),
				List.of(new Operation(Root.MODPACK_DIR, "mods/new.jar", OperationType.INSTALL_OBJECT, targetHash, targetBytes.length, null),
						new Operation(Root.GAME_DIR, "config/old.txt", OperationType.DELETE, null, -1, oldHash)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, targetHash, targetBytes.length),
						new ProjectedFile(Root.GAME_DIR, "config/old.txt", false, null, -1)),
				clientConfig(target.manifest().modpackId()), Set.of(RestartReason.APPLIED_SERVER_DELETIONS),
				List.of(new Preservation(Root.GAME_DIR, "config/old.txt", oldHash, oldBytes.length)),
				List.of(new BaselineCapture(Root.GAME_DIR, "config/old.txt", oldHash, oldBytes.length, false)));

		UpdateTransactionExecutor.Execution result = executor(paths, null).commit(plan, target);

		assertTrue(result.success());
		assertFalse(Files.exists(oldFile));
		assertArrayEquals(oldBytes, Files.readAllBytes(paths.store().resolve(oldHash)));
		Jsons.ClientBaselineFields baseline = ConfigTools.read(paths.modpack().resolve("automodpack-baseline.json"), Jsons.ClientBaselineFields.class).orElseThrow();
		assertEquals("abc1234", baseline.modpackId);
		assertEquals("config/old.txt", baseline.entries.get(0).logicalPath);
		assertEquals(oldHash, baseline.entries.get(0).objectHash);
	}

	@Test
	void removalRestoresBaselineAndPreservesServerDeletedBytes() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		Files.createDirectories(paths.modpack().resolve("config"));
		Files.createDirectories(paths.game().resolve("config"));

		byte[] removedBytes = "server-deleted".getBytes(StandardCharsets.UTF_8);
		String removedHash = store(paths, removedBytes);
		byte[] packBytes = "pack-file".getBytes(StandardCharsets.UTF_8);
		String packHash = store(paths, packBytes);
		byte[] baselineBytes = "player-file".getBytes(StandardCharsets.UTF_8);
		String baselineHash = store(paths, baselineBytes);

		Jsons.CompleteModpackContentFields oldFields = new Jsons.CompleteModpackContentFields();
		oldFields.modpackId = "abc1234";
		oldFields.selectionTags = Map.of();
		var oldGroup = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		oldGroup.files = Map.of("config/removed.cfg", new Jsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(removedBytes.length), "config", false, false,
				false, removedHash, null));
		oldFields.groups = Map.of("main", oldGroup);
		GenerationRecord oldRecord = GenerationRecord.create(GroupManifestValidator.validate(oldFields), null, Instant.parse("2026-01-01T00:00:00Z"), "");

		Jsons.CompleteModpackContentFields targetFields = new Jsons.CompleteModpackContentFields();
		targetFields.modpackId = "abc1234";
		targetFields.selectionTags = Map.of();
		var targetGroup = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		targetGroup.files = Map.of("config/pack.cfg", new Jsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(packBytes.length), "config", false, false,
				false, packHash, null));
		targetFields.groups = Map.of("main", targetGroup);
		GenerationRecord targetRecord = GenerationRecord.create(GroupManifestValidator.validate(targetFields), oldRecord, Instant.parse("2026-01-02T00:00:00Z"), "");
		SelectedModpackTarget target = SelectedModpackTarget.prepare(targetRecord.toFields(), null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);

		Files.write(paths.modpack().resolve("config/pack.cfg"), packBytes);
		Files.write(paths.game().resolve("config/pack.cfg"), packBytes);
		Files.write(paths.game().resolve("config/removed.cfg"), removedBytes);
		ConfigTools.writeAtomic(paths.catalogue(), targetRecord.toFields());
		ModpackContentTools.write(paths.manifest(), target.flatTarget());

		Jsons.ClientBaselineFields baseline = new Jsons.ClientBaselineFields();
		baseline.modpackId = "abc1234";
		Jsons.ClientBaselineFields.EntryFields restored = new Jsons.ClientBaselineFields.EntryFields();
		restored.logicalPath = "config/pack.cfg";
		restored.objectHash = baselineHash;
		restored.size = baselineBytes.length;
		Jsons.ClientBaselineFields.EntryFields absent = new Jsons.ClientBaselineFields.EntryFields();
		absent.logicalPath = "config/removed.cfg";
		absent.absent = true;
		baseline.entries = List.of(restored, absent);
		ConfigTools.writeAtomic(paths.modpack().resolve("automodpack-baseline.json"), baseline);

		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.MODPACK_DIR, "config/pack.cfg"), new FileState(packHash, packBytes.length, true, false),
				new FileKey(Root.GAME_DIR, "config/pack.cfg"), new FileState(packHash, packBytes.length, true, false),
				new FileKey(Root.GAME_DIR, "config/removed.cfg"), new FileState(removedHash, removedBytes.length, true, false));
		Jsons.ClientConfigFieldsV3 plannedConfig = new Jsons.ClientConfigFieldsV3();
		UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(target.flatTarget(), baseline, files, Set.of(baselineHash), plannedConfig));
		SelectionIntent expectedSelection = new SelectionIntent(Set.of("main"));
		new ClientSelectionStore(paths.selection()).compareAndSet("abc1234", null, expectedSelection);
		UpdateTransaction transaction = UpdateTransaction.createRemoval(plan, targetRecord.toFields(), target.flatTarget(), paths.modpack(), ClientPlatform.LINUX,
				expectedSelection);

		UpdateTransactionExecutor.Execution execution = executor(paths, null).commit(transaction);

		assertTrue(execution.success());
		assertArrayEquals(baselineBytes, Files.readAllBytes(paths.game().resolve("config/pack.cfg")));
		assertFalse(Files.exists(paths.game().resolve("config/removed.cfg")));
		assertFalse(Files.exists(paths.modpack().resolve("config/pack.cfg")));
		assertFalse(Files.exists(paths.manifest()));
		assertFalse(Files.exists(paths.catalogue()));
		assertFalse(Files.exists(paths.modpack().resolve("automodpack-baseline.json")));
		assertArrayEquals(removedBytes, Files.readAllBytes(paths.store().resolve(removedHash)));
		assertTrue(new ClientSelectionStore(paths.selection()).get("abc1234").isEmpty());
		assertFalse(Files.exists(paths.transaction()));
	}

	@Test
	void removalRejectsLiveRestoreTargetChangedAfterPlanning() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		Files.createDirectories(paths.modpack().resolve("config"));
		Files.createDirectories(paths.game().resolve("config"));
		byte[] packBytes = "pack-file".getBytes(StandardCharsets.UTF_8);
		String packHash = store(paths, packBytes);
		byte[] baselineBytes = "player-file".getBytes(StandardCharsets.UTF_8);
		String baselineHash = store(paths, baselineBytes);

		Jsons.CompleteModpackContentFields fields = new Jsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.selectionTags = Map.of();
		var group = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		group.files = Map.of("config/pack.cfg", new Jsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(packBytes.length), "config", false, false, false, packHash, null));
		fields.groups = Map.of("main", group);
		GenerationRecord record = GenerationRecord.create(GroupManifestValidator.validate(fields), null, Instant.parse("2026-01-01T00:00:00Z"), "");
		SelectedModpackTarget target = SelectedModpackTarget.prepare(record.toFields(), null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
		Files.write(paths.modpack().resolve("config/pack.cfg"), packBytes);
		Path live = Files.write(paths.game().resolve("config/pack.cfg"), packBytes);
		ConfigTools.writeAtomic(paths.catalogue(), record.toFields());
		ModpackContentTools.write(paths.manifest(), target.flatTarget());
		Jsons.ClientBaselineFields baseline = new Jsons.ClientBaselineFields();
		baseline.modpackId = "abc1234";
		Jsons.ClientBaselineFields.EntryFields entry = new Jsons.ClientBaselineFields.EntryFields();
		entry.logicalPath = "config/pack.cfg";
		entry.objectHash = baselineHash;
		entry.size = baselineBytes.length;
		baseline.entries = List.of(entry);
		Jsons.ClientConfigFieldsV3 plannedConfig = new Jsons.ClientConfigFieldsV3();
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.MODPACK_DIR, "config/pack.cfg"), new FileState(packHash, packBytes.length, true, false),
				new FileKey(Root.GAME_DIR, "config/pack.cfg"), new FileState(packHash, packBytes.length, true, false));
		UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(target.flatTarget(), baseline, files, Set.of(baselineHash), plannedConfig));
		UpdateTransaction transaction = UpdateTransaction.createRemoval(plan, record.toFields(), target.flatTarget(), paths.modpack(), ClientPlatform.LINUX);
		Files.writeString(live, "changed-after-planning", StandardCharsets.UTF_8);

		assertThrows(UpdateExecutionException.class, () -> executor(paths, null).commit(transaction));
		assertTrue(Files.exists(paths.manifest()));
		assertTrue(Files.exists(paths.catalogue()));
		assertEquals("changed-after-planning", Files.readString(live, StandardCharsets.UTF_8));
	}

	@Test
	void rejectsGenerationIdentityMismatchesBeforeFileMutation() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		byte[] bytes = "identity-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(paths, bytes);
		Jsons.ModpackContentFields manifest = manifest(hash, bytes.length);
		SelectedModpackTarget target = selectedTarget(manifest);
		UpdatePlan plan = new UpdatePlan(manifest.modpackId, target.generationTarget(),
				List.of(new Operation(Root.MODPACK_DIR, "mods/new.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, hash, bytes.length)), clientConfig(manifest.modpackId), Set.of());

		List<UpdateTransaction> mismatches = new ArrayList<>();
		UpdateTransaction tamperedTargetId = UpdateTransaction.create(plan, target, paths.modpack());
		tamperedTargetId.targetGenerationId = "f".repeat(40);
		mismatches.add(tamperedTargetId);
		UpdateTransaction tamperedParentId = UpdateTransaction.create(plan, target, paths.modpack());
		tamperedParentId.parentGenerationId = "e".repeat(40);
		mismatches.add(tamperedParentId);
		UpdateTransaction tamperedStateDigest = UpdateTransaction.create(plan, target, paths.modpack());
		tamperedStateDigest.stateDigest = "d".repeat(40);
		mismatches.add(tamperedStateDigest);
		UpdateTransaction tamperedLedgerDigest = UpdateTransaction.create(plan, target, paths.modpack());
		tamperedLedgerDigest.ledgerDigest = "a".repeat(40);
		mismatches.add(tamperedLedgerDigest);
		UpdateTransaction tamperedModpackId = UpdateTransaction.create(plan, target, paths.modpack());
		tamperedModpackId.modpackId = "def5678";
		mismatches.add(tamperedModpackId);
		UpdateTransaction tamperedFlat = UpdateTransaction.create(plan, target, paths.modpack());
		Jsons.ModpackContentFields flat = tamperedFlat.targetManifest();
		flat.targetGenerationId = "c".repeat(40);
		tamperedFlat.targetManifestJson = ConfigTools.GSON.toJson(flat);
		mismatches.add(tamperedFlat);
		UpdateTransaction tamperedRecord = UpdateTransaction.create(plan, target, paths.modpack());
		Jsons.CompleteModpackContentFields complete = target.completeFields();
		complete.generation.generationId = "b".repeat(40);
		tamperedRecord.completeManifestJson = ConfigTools.GSON.toJson(complete);
		mismatches.add(tamperedRecord);

		for (UpdateTransaction mismatch : mismatches) {
			assertThrows(IOException.class, () -> executor(paths, null).commit(mismatch));
			assertFalse(Files.exists(paths.transaction()));
			assertFalse(Files.exists(paths.modpack().resolve("mods/new.jar")));
		}
	}

	@Test
	void directSkippedGenerationUpdateConvergesWithoutIntermediateRecords() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		Files.createDirectories(paths.modpack().resolve("mods"));
		byte[] oldBytes = "generation-a".getBytes(StandardCharsets.UTF_8);
		String oldHash = store(paths, oldBytes);
		Jsons.ModpackContentFields oldManifest = manifest(oldHash, oldBytes.length);
		oldManifest.list = Set.of(new Jsons.ModpackContentFields.ModpackContentItem("/mods/old.jar", String.valueOf(oldBytes.length), "mod", false,
				false, false, oldHash, "0"));
		SelectedModpackTarget oldTarget = selectedTarget(oldManifest, "", "2026-01-01T00:00:00Z");
		ConfigTools.writeAtomic(paths.catalogue(), oldTarget.completeFields());
		ModpackContentTools.write(paths.manifest(), oldTarget.flatTarget());
		Files.write(paths.modpack().resolve("mods/old.jar"), oldBytes);

		byte[] newBytes = "generation-d".getBytes(StandardCharsets.UTF_8);
		String newHash = store(paths, newBytes);
		Jsons.ModpackContentFields newManifest = manifest(newHash, newBytes.length);
		SelectedModpackTarget newTarget = selectedTarget(newManifest, "c".repeat(40), "2026-01-02T00:00:00Z");
		UpdatePlan plan = new UpdatePlan(newManifest.modpackId, newTarget.generationTarget(),
				List.of(new Operation(Root.MODPACK_DIR, "mods/new.jar", OperationType.INSTALL_OBJECT, newHash, newBytes.length, null),
						new Operation(Root.MODPACK_DIR, "mods/old.jar", OperationType.DELETE, null, -1, oldHash)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, newHash, newBytes.length),
						new ProjectedFile(Root.MODPACK_DIR, "mods/old.jar", false, null, -1)),
				clientConfig(newManifest.modpackId), Set.of());

		UpdateTransactionExecutor.Execution result = executor(paths, null).commit(plan, newTarget);

		assertTrue(result.success());
		assertEquals(newTarget.generationRecord(), ModpackContentTools.readGenerationRecord(paths.catalogue()));
		assertEquals(newTarget.generationTarget().targetGenerationId(), ModpackContentTools.read(paths.manifest()).targetGenerationId);
		assertFalse(Files.exists(paths.modpack().resolve("mods/old.jar")));
	}

	@Test
	void recoveryAfterCataloguePublicationConvergesBothGenerationFiles() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		byte[] bytes = "catalogue-before-manifest".getBytes(StandardCharsets.UTF_8);
		String hash = store(paths, bytes);
		Jsons.ModpackContentFields manifest = manifest(hash, bytes.length);
		SelectedModpackTarget target = selectedTarget(manifest);
		UpdatePlan plan = new UpdatePlan(manifest.modpackId, target.generationTarget(),
				List.of(new Operation(Root.MODPACK_DIR, "mods/new.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, hash, bytes.length)), clientConfig(manifest.modpackId), Set.of());
		UpdateTransactionExecutor executor = executor(paths,
				transaction -> {
					assertTrue(new ClientSelectionStore(paths.selection()).get(manifest.modpackId).isEmpty());
					if (!Files.exists(paths.catalogue(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(paths.manifest());
				});

		assertThrows(UpdateExecutionException.class, () -> executor.commit(plan, target));
		assertTrue(Files.isRegularFile(paths.catalogue(), LinkOption.NOFOLLOW_LINKS));
		assertTrue(Files.isDirectory(paths.manifest(), LinkOption.NOFOLLOW_LINKS));
		UpdateTransaction persisted = executor.readPersisted();
		assertNotNull(persisted);
		Files.delete(paths.manifest());

		UpdateTransactionExecutor.Execution recovered = executor.recover(persisted);

		assertTrue(recovered.success());
		assertFalse(Files.exists(paths.transaction()));
		assertEquals(target.generationRecord(), ModpackContentTools.readGenerationRecord(paths.catalogue()));
		assertEquals(target.generationTarget(), GenerationTarget.fromFlat(ModpackContentTools.read(paths.manifest())));
		assertEquals(target.selection().intent(), new ClientSelectionStore(paths.selection()).get(manifest.modpackId).orElseThrow());
	}

	@Test
	void rejectsUnrelatedStoredCatalogueAndManifestBeforeFileMutation() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		byte[] oldBytes = "stored-catalogue".getBytes(StandardCharsets.UTF_8);
		String oldHash = store(paths, oldBytes);
		Jsons.ModpackContentFields oldManifest = manifest(oldHash, oldBytes.length);
		SelectedModpackTarget oldTarget = selectedTarget(oldManifest, "", "2026-01-01T00:00:00Z");
		byte[] otherBytes = "stored-manifest".getBytes(StandardCharsets.UTF_8);
		String otherHash = store(paths, otherBytes);
		Jsons.ModpackContentFields otherManifest = manifest(otherHash, otherBytes.length);
		SelectedModpackTarget otherTarget = selectedTarget(otherManifest, oldTarget.generationTarget().targetGenerationId(), "2026-01-02T00:00:00Z");
		ConfigTools.writeAtomic(paths.catalogue(), oldTarget.completeFields());
		ModpackContentTools.write(paths.manifest(), otherTarget.flatTarget());
		byte[] targetBytes = "new-target".getBytes(StandardCharsets.UTF_8);
		String targetHash = store(paths, targetBytes);
		Jsons.ModpackContentFields targetManifest = manifest(targetHash, targetBytes.length);
		SelectedModpackTarget target = selectedTarget(targetManifest, otherTarget.generationTarget().targetGenerationId(), "2026-01-03T00:00:00Z");
		UpdatePlan plan = new UpdatePlan(targetManifest.modpackId, target.generationTarget(),
				List.of(new Operation(Root.MODPACK_DIR, "mods/new.jar", OperationType.INSTALL_OBJECT, targetHash, targetBytes.length, null)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, targetHash, targetBytes.length)), clientConfig(targetManifest.modpackId), Set.of());

		assertThrows(IOException.class, () -> executor(paths, null).commit(plan, target));
		assertFalse(Files.exists(paths.transaction()));
		assertFalse(Files.exists(paths.modpack().resolve("mods/new.jar")));
		assertEquals(oldTarget.generationRecord(), ModpackContentTools.readGenerationRecord(paths.catalogue()));
		assertEquals(otherTarget.generationTarget().targetGenerationId(), ModpackContentTools.read(paths.manifest()).targetGenerationId);
	}

	@Test
	void recoveryAfterManifestPublicationConvergesAndClearsTransaction() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		byte[] bytes = "published-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(paths, bytes);
		Jsons.ModpackContentFields manifest = manifest(hash, bytes.length);
		UpdatePlan plan = new UpdatePlan(manifest.modpackId, selectedTarget(manifest).generationTarget(),
				List.of(new Operation(Root.MODPACK_DIR, "mods/new.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, hash, bytes.length)), clientConfig(manifest.modpackId), Set.of());
		UpdateTransactionExecutor executor = executor(paths, null);
		UpdateTransactionExecutor.Execution committed = executor.commit(plan, selectedTarget(manifest));
		ConfigTools.writeAtomic(paths.transaction(), committed.transaction());

		UpdateTransactionExecutor.Execution recovered = executor.recover(committed.transaction());

		assertTrue(recovered.success());
		assertFalse(Files.exists(paths.transaction()));
		assertEquals(manifest.modpackId, ModpackContentTools.read(paths.manifest()).modpackId);
	}

	@Test
	void completeFinalStateDetectsAFileChangedDuringCommitAndKeepsTransaction() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.modpack().resolve("config"));
		Path unchanged = Files.writeString(paths.modpack().resolve("config/settings.json"), "planned", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(unchanged);
		Jsons.ModpackContentFields manifest = editableManifest(hash, Files.size(unchanged));
		UpdatePlan plan = new UpdatePlan(manifest.modpackId, selectedTarget(manifest).generationTarget(), List.of(),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "config/settings.json", true, hash, Files.size(unchanged))), clientConfig(manifest.modpackId), Set.of());

		assertThrows(Exception.class, () -> executor(paths, ignored -> Files.writeString(unchanged, "changed during commit", StandardCharsets.UTF_8)).commit(plan, selectedTarget(manifest)));
		assertTrue(Files.exists(paths.transaction()));
		assertFalse(Files.exists(paths.manifest()));
	}

	@Test
	void rejectsTraversalAndTamperedProjectionBeforeMutation() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		byte[] bytes = {1};
		String hash = store(paths, bytes);
		Jsons.ModpackContentFields manifest = manifest(hash, bytes.length);
		UpdatePlan traversal = new UpdatePlan(manifest.modpackId, selectedTarget(manifest).generationTarget(),
				List.of(new Operation(Root.MODPACK_DIR, "../escape.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, hash, bytes.length)), clientConfig(manifest.modpackId), Set.of());
		assertThrows(Exception.class, () -> executor(paths, null).commit(traversal, selectedTarget(manifest)));

		UpdatePlan valid = new UpdatePlan(manifest.modpackId, selectedTarget(manifest).generationTarget(),
				List.of(new Operation(Root.MODPACK_DIR, "mods/new.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, hash, bytes.length)), clientConfig(manifest.modpackId), Set.of());
		UpdateTransaction tampered = UpdateTransaction.create(valid, selectedTarget(manifest), paths.modpack());
		tampered.projectedFinalState = List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, "f".repeat(40), bytes.length));
		assertThrows(Exception.class, () -> executor(paths, null).recover(tampered));

		UpdateTransaction aliased = UpdateTransaction.create(valid, selectedTarget(manifest), paths.modpack());
		aliased.projectedFinalState = List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, hash, bytes.length),
				new ProjectedFile(Root.AUTOMODPACK_DIR, "modpacks/abc1234/mods/new.jar", true, hash, bytes.length));
		assertThrows(Exception.class, () -> executor(paths, null).recover(aliased));

		UpdateTransaction protectedCatalogue = UpdateTransaction.create(valid, selectedTarget(manifest), paths.modpack());
		protectedCatalogue.operations = List.of(new Operation(Root.MODPACK_DIR, "automodpack-catalogue.json", OperationType.INSTALL_OBJECT, hash, bytes.length, null));
		protectedCatalogue.projectedFinalState = List.of(new ProjectedFile(Root.MODPACK_DIR, "automodpack-catalogue.json", true, hash, bytes.length));
		assertThrows(IOException.class, () -> executor(paths, null).recover(protectedCatalogue));
		assertFalse(Files.exists(paths.catalogue()));
		assertFalse(Files.exists(paths.transaction()));
		assertFalse(Files.exists(paths.modpack().resolve("mods/new.jar")));
	}

	@Test
	void rejectsSymlinkedParentEscapingConstrainedRoot() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		Files.createDirectories(paths.modpack());
		Path outside = temporaryDirectory.resolve("outside");
		Files.createDirectories(outside);
		try {
			Files.createSymbolicLink(paths.modpack().resolve("linked"), outside);
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e);
		}
		byte[] bytes = "escaped-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(paths, bytes);
		Jsons.ModpackContentFields manifest = new Jsons.ModpackContentFields(Set.of(
				new Jsons.ModpackContentFields.ModpackContentItem("/linked/new.jar", String.valueOf(bytes.length), "other", false, false, false, hash, "0")));
		manifest.modpackId = "abc1234";
		UpdatePlan plan = new UpdatePlan(manifest.modpackId, selectedTarget(manifest).generationTarget(),
				List.of(new Operation(Root.MODPACK_DIR, "linked/new.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "linked/new.jar", true, hash, bytes.length)), clientConfig(manifest.modpackId), Set.of());

		assertThrows(IOException.class, () -> executor(paths, null).commit(plan, selectedTarget(manifest)));
		assertFalse(Files.exists(outside.resolve("new.jar")));
		assertFalse(Files.exists(paths.transaction()));
	}

	@Test
	void changedSelectionRejectsTransactionBeforeFileMutation() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		byte[] bytes = "target-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(paths, bytes);
		Jsons.ModpackContentFields manifest = manifest(hash, bytes.length);
		UpdatePlan plan = new UpdatePlan(manifest.modpackId, selectedTarget(manifest).generationTarget(),
				List.of(new Operation(Root.MODPACK_DIR, "mods/new.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, hash, bytes.length)), clientConfig(manifest.modpackId), Set.of());
		new ClientSelectionStore(paths.selection()).compareAndSet(manifest.modpackId, null, new SelectionIntent(Set.of("visuals")));

		assertThrows(IOException.class, () -> executor(paths, null).commit(plan, selectedTarget(manifest)));
		assertFalse(Files.exists(paths.transaction()));
		assertFalse(Files.exists(paths.modpack().resolve("mods/new.jar")));
	}

	@Test
	void selectionIsNotClaimedWhenTransactionCannotBePersisted() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		byte[] bytes = "target-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(paths, bytes);
		Jsons.ModpackContentFields manifest = manifest(hash, bytes.length);
		UpdatePlan plan = new UpdatePlan(manifest.modpackId, selectedTarget(manifest).generationTarget(),
				List.of(new Operation(Root.MODPACK_DIR, "mods/new.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, hash, bytes.length)), clientConfig(manifest.modpackId), Set.of());
		Files.createDirectories(paths.automodpack());
		Files.writeString(paths.automodpack().resolve(".private"), "blocked", StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> executor(paths, null).commit(plan, selectedTarget(manifest)));
		assertTrue(new ClientSelectionStore(paths.selection()).get(manifest.modpackId).isEmpty());
	}

	@Test
	void selfUpdateUsesConstrainedCasOperationsWithoutPublishingModpackState() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		Files.createDirectories(paths.mods());
		Path currentJar = Files.writeString(paths.mods().resolve("automodpack-old.jar"), "old", StandardCharsets.UTF_8);
		String currentHash = HashUtils.getHash(currentJar);
		byte[] replacement = "official-update".getBytes(StandardCharsets.UTF_8);
		String replacementHash = store(paths, replacement);
		UpdateTransaction transaction = UpdateTransaction.createSelfUpdate(currentJar.getFileName().toString(), "automodpack-new.jar", replacementHash,
				replacement.length, currentHash);

		UpdateTransactionExecutor.Execution execution = executor(paths, null).commit(transaction);

		assertTrue(execution.success());
		assertFalse(Files.exists(currentJar));
		assertArrayEquals(replacement, Files.readAllBytes(paths.mods().resolve("automodpack-new.jar")));
		assertFalse(Files.exists(paths.manifest()));
		assertFalse(Files.exists(paths.clientConfig()));
		assertFalse(Files.exists(paths.transaction()));

		UpdateTransaction tampered = UpdateTransaction.createSelfUpdate("automodpack-old.jar", "../outside.jar", replacementHash, replacement.length, currentHash);
		assertThrows(IOException.class, () -> executor(paths, null).validate(tampered));
	}

	private UpdateTransactionExecutor executor(Paths paths, UpdateTransactionExecutor.CommitAction action) {
		return new UpdateTransactionExecutor(new UpdateTransactionExecutor.Context(paths.game(), paths.modpack(), paths.mods(), paths.store(), paths.automodpack(),
				paths.transaction(), paths.result(), paths.clientConfig(), paths.manifest(), paths.catalogue(), paths.selection(), action));
	}

	private Paths paths() {
		Path game = temporaryDirectory.resolve("game");
		Path automodpack = game.resolve("automodpack");
		Path modpack = automodpack.resolve("modpacks/abc1234");
		return new Paths(game, modpack, game.resolve("mods"), automodpack.resolve("store"), automodpack,
				automodpack.resolve(".private/update-transaction.json"), automodpack.resolve(".private/update-transaction-result.json"),
				automodpack.resolve("automodpack-client.json"),
				modpack.resolve("automodpack-content.json"), modpack.resolve("automodpack-catalogue.json"),
				automodpack.resolve("automodpack-client-selection.json"));
	}

	private static String store(Paths paths, byte[] bytes) throws Exception {
		Path temporaryObject = Files.write(paths.store().resolve("object.tmp"), bytes);
		String hash = HashUtils.getHash(temporaryObject);
		Files.move(temporaryObject, paths.store().resolve(hash));
		return hash;
	}

	private static Jsons.ClientConfigFieldsV3 clientConfig(String modpackId) {
		Jsons.ClientConfigFieldsV3 config = new Jsons.ClientConfigFieldsV3();
		config.selectedModpackId = modpackId;
		config.modpackConnections.put(modpackId,
				new Jsons.ConnectionInfo(InetSocketAddress.createUnresolved("origin.example", 25565), InetSocketAddress.createUnresolved("endpoint.example", 25564),
						ModpackConnectionMode.MAGIC_PACKET, null, null));
		return config;
	}

	private static Jsons.ModpackContentFields manifest(String hash, long size) {
		Jsons.ModpackContentFields manifest = new Jsons.ModpackContentFields(Set.of(
				new Jsons.ModpackContentFields.ModpackContentItem("/mods/new.jar", String.valueOf(size), "mod", false, false, false, hash, "0")));
		manifest.modpackId = "abc1234";
		manifest.modpackName = "Test";
		return manifest;
	}

	private static Jsons.ModpackContentFields editableManifest(String hash, long size) {
		Jsons.ModpackContentFields manifest = new Jsons.ModpackContentFields(Set.of(new Jsons.ModpackContentFields.ModpackContentItem("/config/settings.json",
				String.valueOf(size), "config", true, false, false, hash, "0")));
		manifest.modpackId = "abc1234";
		return manifest;
	}

	private static SelectedModpackTarget selectedTarget(Jsons.ModpackContentFields manifest) {
		return selectedTarget(manifest, "", "2026-01-01T00:00:00Z");
	}

	private static SelectedModpackTarget selectedTarget(Jsons.ModpackContentFields manifest, String parentGenerationId, String createdAt) {
		Jsons.CompleteModpackContentFields fields = new Jsons.CompleteModpackContentFields();
		fields.modpackId = manifest.modpackId;
		fields.modpackName = manifest.modpackName;
		fields.automodpackVersion = manifest.automodpackVersion;
		fields.loader = manifest.loader;
		fields.loaderVersion = manifest.loaderVersion;
		fields.mcVersion = manifest.mcVersion;
		Jsons.CompleteModpackContentFields.ModpackGroupFields group = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		Map<String, Jsons.CompleteModpackContentFields.GroupFileFields> files = new LinkedHashMap<>();
		for (var item : manifest.list)
			files.put(UpdatePlanner.normalize(item.file), new Jsons.CompleteModpackContentFields.GroupFileFields(item.size,
					item.type, item.editable, item.overwriteEditable, item.forceCopy, item.sha1, item.murmur));
		group.files = files;
		fields.groups = Map.of("main", group);
		GenerationRecord record = GenerationRecord.create(GroupManifestValidator.validate(fields), null, Instant.parse(createdAt), "");
		manifest.targetGenerationId = record.metadata().generationId();
		manifest.parentGenerationId = record.metadata().parentGenerationId();
		manifest.stateDigest = record.metadata().stateDigest();
		return SelectedModpackTarget.prepare(record.toFields(), null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
	}

	private record Paths(Path game, Path modpack, Path mods, Path store, Path automodpack, Path transaction, Path result, Path clientConfig,
			Path manifest, Path catalogue, Path selection) {}
}
