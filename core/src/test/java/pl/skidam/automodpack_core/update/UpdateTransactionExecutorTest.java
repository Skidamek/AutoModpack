package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

class UpdateTransactionExecutorTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void commitsAnImmutableProjectionFromCas() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "projection-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(storage, bytes);
		SelectedModpackTarget target = target("mods/new.jar", "mod", false, hash, bytes.length);
		Path projectionFile = storage.activePath("mods/new.jar");
		UpdatePlan plan = plan(target, clientConfig(target.manifest().modpackId()),
				List.of(new Operation(Root.PROJECTION, "mods/new.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/new.jar", true, hash, bytes.length)));

		UpdateTransactionExecutor.Execution execution = executor(storage).commit(plan, target);

		assertTrue(execution.success());
		assertTrue(SmartFileUtils.isValidFile(projectionFile, bytes.length, hash));
		assertVerifiedObjectProjection(storage.objectsDirectory().resolve(hash), projectionFile, bytes.length, hash);
		assertEquals(target.generationTarget().targetGenerationId(), storage.readActiveState().generationId);
		assertEquals(target.generationRecord(), new ClientGenerationStore(storage).read(target.generationTarget().targetGenerationId()).orElseThrow());
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
		SelectedModpackTarget target = target("mods/root.jar", "mod", false, rootHash, rootBytes.length);
		UpdatePlan.NestedCopy generated = new UpdatePlan.NestedCopy("mods/nested.jar", nestedHash, nestedBytes.length, Set.of("nested"));
		UpdatePlan plan = new UpdatePlan(target.manifest().modpackId(), target.generationTarget(), List.of(
				new Operation(Root.PROJECTION, "mods/root.jar", OperationType.INSTALL_OBJECT, rootHash, rootBytes.length, null),
				new Operation(Root.GAME_DIR, "mods/nested.jar", OperationType.INSTALL_OBJECT, nestedHash, nestedBytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/root.jar", true, rootHash, rootBytes.length),
						new ProjectedFile(Root.GAME_DIR, "mods/nested.jar", true, nestedHash, nestedBytes.length)),
				clientConfig(target.manifest().modpackId()), Set.of(UpdatePlan.RestartReason.FIXED_NESTED_MODS), List.of(), List.of(), List.of(), List.of(generated));

		assertTrue(executor(storage).commit(plan, target).success());

		GeneratedCopyState state = GeneratedCopyState.read(storage, target.manifest().modpackId(), target.generationTarget().targetGenerationId(),
				UpdateTransaction.digest(target.selection().intent()));
		assertEquals(List.of(new GeneratedCopyState.Entry("mods/nested.jar", nestedHash, nestedBytes.length)), state.entries());
		assertTrue(SmartFileUtils.isValidFile(storage.modsDirectory().resolve("nested.jar"), nestedBytes.length, nestedHash));
	}

	@Test
	void metadataOnlyUpdateKeepsProjectionAndDoesNotRequireCasObject() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "metadata-only-projection".getBytes(StandardCharsets.UTF_8);
		Files.createDirectories(storage.activePath("mods"));
		String hash = HashUtils.getHash(Files.write(storage.activePath("mods/existing.jar"), bytes));
		SelectedModpackTarget target = target("mods/existing.jar", "mod", false, hash, bytes.length);
		UpdatePlan plan = new UpdatePlan(target.manifest().modpackId(), target.generationTarget(), List.of(),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/existing.jar", true, hash, bytes.length)), clientConfig(target.manifest().modpackId()), Set.of(), List.of(), List.of(), List.of(), List.of());

		UpdateTransactionExecutor.Execution execution = executor(storage).commit(plan, target);

		assertTrue(execution.success());
		assertTrue(SmartFileUtils.isValidFile(storage.activePath("mods/existing.jar"), bytes.length, hash));
		assertFalse(Files.exists(storage.objectsDirectory().resolve(hash)));
		assertEquals(target.generationTarget().targetGenerationId(), storage.readActiveState().generationId);
	}

	@Test
	void firstInstallQuarantinesLocalSameIdModBeforeProjectionApply() throws Exception {
		ClientStorage storage = storage();
		byte[] serverBytes = "server-sodium".getBytes(StandardCharsets.UTF_8);
		String serverHash = store(storage, serverBytes);
		Path local = storage.modsDirectory().resolve("local-sodium.jar");
		byte[] localBytes = "local-sodium".getBytes(StandardCharsets.UTF_8);
		Files.write(local, localBytes);
		String localHash = HashUtils.getHash(local);
		SelectedModpackTarget target = target("mods/server-sodium.jar", "mod", false, serverHash, serverBytes.length);
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = Map.of(new UpdatePlan.FileKey(Root.GAME_DIR, "mods/local-sodium.jar"),
				new UpdatePlan.FileState(localHash, localBytes.length, true));
		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target.flatTarget(), files, Map.of(), Set.of(),
				List.of(new UpdatePlan.ModInfo("mods/server-sodium.jar", serverHash, serverBytes.length, Set.of("sodium"), Set.of())),
				List.of(new UpdatePlan.ModInfo("mods/local-sodium.jar", localHash, localBytes.length, Set.of("sodium"), Set.of())), List.of(), List.of(), null,
				clientConfig(target.manifest().modpackId())));
		UpdateTransaction malformed = UpdateTransaction.create(plan, target, storage.overlayDigest(target.manifest().modpackId()));
		malformed.plannedConflicts = new ArrayList<>(List.of(plan.conflicts().get(0)));
		malformed.plannedConflicts.set(0, null);
		assertThrows(IOException.class, () -> executor(storage).validate(malformed));

		UpdateTransactionExecutor.Execution execution = executor(storage).commit(plan, target);

		assertTrue(execution.success());
		assertFalse(Files.exists(local));
		assertTrue(SmartFileUtils.isValidFile(storage.activePath("mods/server-sodium.jar"), serverBytes.length, serverHash));
		ClientStorageJsons.ClientQuarantineFields quarantine = QuarantineArchive.read(storage, target.manifest().modpackId());
		assertEquals(1, quarantine.entries.size());
		assertTrue(SmartFileUtils.isValidFile(storage.quarantinePayload(target.manifest().modpackId(), plan.conflicts().get(0).conflictId()), localBytes.length, localHash));
	}

	@Test
	void refusesPlanBeforePersistingTargetWhenAnotherTransactionIsActive() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "blocked-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(storage, bytes);
		SelectedModpackTarget target = target("mods/blocked.jar", "mod", false, hash, bytes.length);
		UpdatePlan plan = plan(target, clientConfig(target.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/blocked.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/blocked.jar", true, hash, bytes.length)));
		Files.writeString(storage.transactionFile(), "active", StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> executor(storage).commit(plan, target));
		assertTrue(new ClientGenerationStore(storage).read(target.generationTarget().targetGenerationId()).isEmpty());
	}

	@Test
	void persistsPatchNotesForGenerationsTheClientSkipped() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "history-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(storage, bytes);
		ModpackJsons.CompleteModpackContentFields fields = fields("mods/history.jar", "mod", false, hash, bytes.length);
		GenerationRecord first = GenerationRecord.create(GroupManifestValidator.validate(fields), null, Instant.parse("2026-01-01T00:00:00Z"), "First notes");
		GenerationRecord second = GenerationRecord.create(first.manifest(), first, Instant.parse("2026-01-02T00:00:00Z"), "Second notes");
		List<GenerationPatchNoteHistory.Entry> history = GenerationPatchNoteHistory.fromRecords(List.of(first, second));
		ModpackJsons.CompleteModpackContentFields secondFields = second.toFields();
		GenerationPatchNoteHistory.writeFields(secondFields, history);
		SelectedModpackTarget target = SelectedModpackTarget.prepare(secondFields, null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
		UpdatePlan plan = plan(target, clientConfig(target.manifest().modpackId()), List.of(
				new Operation(Root.PROJECTION, "mods/history.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/history.jar", true, hash, bytes.length)));

		assertTrue(executor(storage).commit(plan, target).success());

		assertEquals(history, new ClientGenerationStore(storage).patchNotesHistory(second.metadata().generationId()));
		assertEquals(List.of(second), new ClientGenerationStore(storage).availableLineage("abc1234", second.metadata().generationId()));
	}

	@Test
	void installedRecordCatalogueKeepsNewestValidPackAndSkipsMalformedRecords() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "catalogue-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(storage, bytes);
		GenerationRecord first = GenerationRecord.create(GroupManifestValidator.validate(fields("mods/catalogue.jar", "mod", false, hash, bytes.length)), null,
				Instant.parse("2026-01-01T00:00:00Z"), "first");
		GenerationRecord second = GenerationRecord.create(first.manifest(), first, Instant.parse("2026-01-02T00:00:00Z"), "second");
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(first);
		generations.write(second);
		String malformedId = "0".repeat(40);
		Files.createDirectories(storage.generationDirectory(malformedId));
		Files.writeString(storage.generationManifest(malformedId), "{}", StandardCharsets.UTF_8);

		assertEquals(List.of(second), generations.installedRecords());
	}

	@Test
	void installsEditableOverlayAsMutableCopyAndCopiesItToTheLiveGamePath() throws Exception {
		ClientStorage storage = storage();
		byte[] baseBytes = "server-base".getBytes(StandardCharsets.UTF_8);
		String baseHash = store(storage, baseBytes);
		byte[] editedBytes = "player-edit".getBytes(StandardCharsets.UTF_8);
		String editedHash = store(storage, editedBytes);
		SelectedModpackTarget target = target("config/settings.json", "config", true, baseHash, baseBytes.length);
		List<Operation> operations = List.of(
				new Operation(Root.PROJECTION, "config/settings.json", OperationType.INSTALL_OBJECT, baseHash, baseBytes.length, null),
				new Operation(Root.OVERLAY, "config/settings.json", OperationType.INSTALL_OBJECT, editedHash, editedBytes.length, null),
				new Operation(Root.GAME_DIR, "config/settings.json", OperationType.INSTALL_OBJECT, editedHash, editedBytes.length, null));
		List<ProjectedFile> finalState = List.of(
				new ProjectedFile(Root.PROJECTION, "config/settings.json", true, baseHash, baseBytes.length),
				new ProjectedFile(Root.OVERLAY, "config/settings.json", true, editedHash, editedBytes.length),
				new ProjectedFile(Root.GAME_DIR, "config/settings.json", true, editedHash, editedBytes.length));

		UpdateTransactionExecutor.Execution execution = executor(storage).commit(plan(target, clientConfig(target.manifest().modpackId()), operations, finalState), target);

		Path overlay = storage.overlayFile(target.manifest().modpackId(), "config/settings.json");
		Path live = storage.gameDirectory().resolve("config/settings.json");
		assertTrue(execution.success());
		assertTrue(SmartFileUtils.isValidFile(overlay, editedBytes.length, editedHash));
		assertFalse(Files.isSameFile(storage.objectsDirectory().resolve(editedHash), live));
		assertArrayEquals(editedBytes, Files.readAllBytes(live));
		assertTrue(SmartFileUtils.isValidFile(storage.activePath("config/settings.json"), baseBytes.length, baseHash));
	}

	@Test
	void removalSwapsToAnEmptyProjectionAndKeepsImmutableGenerationRecords() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "removable-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(storage, bytes);
		SelectedModpackTarget target = target("mods/remove.jar", "mod", false, hash, bytes.length);
		UpdatePlan install = plan(target, clientConfig(target.manifest().modpackId()),
				List.of(new Operation(Root.PROJECTION, "mods/remove.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/remove.jar", true, hash, bytes.length)));
		UpdateTransactionExecutor executor = executor(storage);
		executor.commit(install, target);
		Path live = storage.gameDirectory().resolve("mods/remove.jar");
		Files.write(live, bytes);
		Path generatedLive = storage.gameDirectory().resolve("mods/generated-remove.jar");
		byte[] generatedBytes = "generated-removable-object".getBytes(StandardCharsets.UTF_8);
		Files.write(generatedLive, generatedBytes);
		String generatedHash = HashUtils.getHash(generatedLive);
		SelectionIntent expected = target.selection().intent();
		GeneratedCopyState generatedCopies = new GeneratedCopyState(target.manifest().modpackId(), target.generationTarget().targetGenerationId(),
				UpdateTransaction.digest(expected), List.of(new GeneratedCopyState.Entry("mods/generated-remove.jar", generatedHash, generatedBytes.length)));
		generatedCopies.write(storage);

		ClientStorageJsons.ClientBaselineFields baseline = new ClientStorageJsons.ClientBaselineFields();
		baseline.modpackId = target.manifest().modpackId();
		ClientStorageJsons.ClientBaselineFields.EntryFields baselineEntry = new ClientStorageJsons.ClientBaselineFields.EntryFields();
		baselineEntry.logicalPath = "mods/remove.jar";
		baselineEntry.absent = true;
		baselineEntry.objectHash = "";
		baselineEntry.size = -1;
		baseline.entries = List.of(baselineEntry);
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = Map.of(
				new UpdatePlan.FileKey(Root.PROJECTION, "mods/remove.jar"), new UpdatePlan.FileState(hash, bytes.length, true),
				new UpdatePlan.FileKey(Root.GAME_DIR, "mods/remove.jar"), new UpdatePlan.FileState(hash, bytes.length, true),
				new UpdatePlan.FileKey(Root.GAME_DIR, "mods/generated-remove.jar"), new UpdatePlan.FileState(generatedHash, generatedBytes.length, true));
		ClientConfigJsons.ClientConfigFieldsV3 removalConfig = new ClientConfigJsons.ClientConfigFieldsV3();
		UpdatePlan removal = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(target.flatTarget(), baseline, files, Set.of(), generatedCopies, removalConfig));
		assertEquals(List.of(new UpdatePlan.Preservation(Root.GAME_DIR, "mods/remove.jar", hash, bytes.length)), removal.preservations());
		assertTrue(removal.operations().stream().anyMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("mods/generated-remove.jar")
				&& operation.operation() == OperationType.DELETE && generatedHash.equals(operation.expectedExistingHash())));
		UpdateTransaction transaction = UpdateTransaction.createRemoval(removal, ClientPlatform.LINUX, expected, storage.overlayDigest(target.manifest().modpackId()));
		Files.delete(storage.objectsDirectory().resolve(hash));

		assertTrue(executor.commit(transaction).success());
		assertFalse(Files.exists(live));
		assertFalse(Files.exists(generatedLive));
		assertFalse(Files.exists(storage.generatedCopiesFile(target.manifest().modpackId(), target.generationTarget().targetGenerationId(), UpdateTransaction.digest(expected))));
		assertTrue(SmartFileUtils.isValidFile(storage.objectsDirectory().resolve(hash), bytes.length, hash));
		assertTrue(Files.isDirectory(storage.activeDirectory()));
		try (var paths = Files.list(storage.activeDirectory())) {
			assertEquals(List.of(), paths.toList());
		}
		assertNull(storage.readActiveState());
		assertTrue(new ClientGenerationStore(storage).read(target.generationTarget().targetGenerationId()).isPresent());
		assertTrue(new ClientSelectionStore(storage.selectionFile()).get(target.manifest().modpackId()).isEmpty());
	}

	@Test
	void selfUpdateRemainsAConstrainedCasOperation() throws Exception {
		ClientStorage storage = storage();
		Path current = Files.writeString(storage.modsDirectory().resolve("automodpack-old.jar"), "old", StandardCharsets.UTF_8);
		Path replacementPath = storage.modsDirectory().resolve("automodpack-new.jar");
		String currentHash = HashUtils.getHash(current);
		byte[] replacement = "replacement".getBytes(StandardCharsets.UTF_8);
		String replacementHash = store(storage, replacement);
		String currentPath = UpdatePlanner.normalize(storage.gameDirectory().relativize(current).toString());
		String targetPath = UpdatePlanner.normalize(storage.gameDirectory().relativize(replacementPath).toString());
		UpdateTransaction transaction = UpdateTransaction.createSelfUpdate(currentPath, targetPath, replacementHash, replacement.length, currentHash);

		assertTrue(executor(storage).commit(transaction).success());
		assertFalse(Files.exists(current));
		assertTrue(SmartFileUtils.isValidFile(replacementPath, replacement.length, replacementHash));
		assertNull(storage.readActiveState());

		UpdateTransaction invalid = UpdateTransaction.createSelfUpdate(currentPath, "../outside.jar", replacementHash, replacement.length, currentHash);
		assertThrows(IOException.class, () -> executor(storage).validate(invalid));
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = ClientStorage.fromGameDirectory(temporaryDirectory.resolve("game"));
		storage.ensureRoots();
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private static UpdateTransactionExecutor executor(ClientStorage storage) {
		return new UpdateTransactionExecutor(new UpdateTransactionExecutor.Context(storage, null));
	}

	private static String store(ClientStorage storage, byte[] bytes) throws Exception {
		Path temporary = Files.createTempFile(storage.objectsDirectory(), ".object-", ".tmp");
		Files.write(temporary, bytes);
		String hash = HashUtils.getHash(temporary);
		Path destination = storage.objectsDirectory().resolve(hash);
		if (Files.exists(destination)) {
			assertTrue(SmartFileUtils.isValidFile(destination, bytes.length, hash));
			Files.delete(temporary);
		} else Files.move(temporary, destination);
		return hash;
	}

	private static void assertVerifiedObjectProjection(Path object, Path projection, long size, String hash) throws Exception {
		assertTrue(SmartFileUtils.isValidFile(projection, size, hash));
		if (Files.getFileStore(object).equals(Files.getFileStore(projection))) assertTrue(Files.isSameFile(object, projection));
	}

	private static UpdatePlan plan(SelectedModpackTarget target, ClientConfigJsons.ClientConfigFieldsV3 config, List<Operation> operations, List<ProjectedFile> finalState) {
		return new UpdatePlan(target.manifest().modpackId(), target.generationTarget(), operations, finalState, config, Set.of(UpdatePlan.RestartReason.SELECTED_MODPACK), List.of(), List.of(), List.of(), List.of());
	}

	private static SelectedModpackTarget target(String path, String type, boolean editable, String hash, long size) {
		ModpackJsons.CompleteModpackContentFields fields = fields(path, type, editable, hash, size);
		GenerationRecord record = GenerationRecord.create(GroupManifestValidator.validate(fields), null, Instant.parse("2026-01-01T00:00:00Z"), "");
		return SelectedModpackTarget.prepare(record.toFields(), null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
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
		fields.groups = Map.of("main", group);
		return fields;
	}

	private static ClientConfigJsons.ClientConfigFieldsV3 clientConfig(String modpackId) {
		ClientConfigJsons.ClientConfigFieldsV3 config = new ClientConfigJsons.ClientConfigFieldsV3();
		config.selectedModpackId = modpackId;
		return config;
	}
}
