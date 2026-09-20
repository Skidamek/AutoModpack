package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/** The tombstone semantics of editable files: removal survives until the pack replaces the content, then it comes back. */
class ReconcileEditableStateTest {
	@TempDir
	Path temporaryDirectory;

	private static final String PACK_ID = "abc1234";
	private static final String EDITABLE_PATH = "config/settings.json";
	private static final String REPLACED_HASH = "2222222222222222222222222222222222222222";

	@Test
	void removedEditableFileStaysDeletedUntilThePackReplacesIt() throws Exception {
		ClientStorage storage = storage();
		ModpackJsons.ModpackContentFields packTarget = install(storage);
		ClientUpdatePlanBuilder builder = builder(storage);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			builder.reconcileEditableState(cache, packTarget);
			assertEquals(List.of(EDITABLE_PATH), storage.readOverlayState(PACK_ID).deletedPaths);

			// A same-content update keeps the removal; the pack replacing the content spends it and the file comes back.
			builder.reconcileEditableState(cache, packTarget);
			assertEquals(List.of(EDITABLE_PATH), storage.readOverlayState(PACK_ID).deletedPaths);
			builder.reconcileEditableState(cache, flatTarget(REPLACED_HASH));
			assertTrue(storage.readOverlayState(PACK_ID).deletedPaths.isEmpty());
		}
		assertFalse(Files.exists(storage.gamePath(EDITABLE_PATH)));
	}

	@Test
	void editedEditableFileIsCapturedAsOverlay() throws Exception {
		ClientStorage storage = storage();
		ModpackJsons.ModpackContentFields packTarget = install(storage);
		byte[] edited = "player-edit".getBytes(StandardCharsets.UTF_8);
		write(storage.gamePath(EDITABLE_PATH), edited);
		ClientUpdatePlanBuilder builder = builder(storage);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			builder.reconcileEditableState(cache, packTarget);

			assertTrue(storage.readOverlayState(PACK_ID).deletedPaths.isEmpty());
			assertTrue(Arrays.equals(edited, Files.readAllBytes(storage.overlayFile(PACK_ID, EDITABLE_PATH))));
			assertTrue(Arrays.equals(edited, Files.readAllBytes(storage.gamePath(EDITABLE_PATH))));
		}
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private ClientUpdatePlanBuilder builder(ClientStorage storage) {
		return new ClientUpdatePlanBuilder(storage, new ModpackLoaderService() {
			@Override
			public void loadModpack(ModpackLoadRequest request) {}

			@Override
			public List<FileInspection.Mod> getModpackNestedConflicts(Path activeProjectionDirectory, FileCache cache) {
				return List.of();
			}
		}, "fabric");
	}

	private static ModpackJsons.ModpackContentFields install(ClientStorage storage) throws Exception {
		byte[] packBytes = "server-default".getBytes(StandardCharsets.UTF_8);
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = PACK_ID;
		fields.modpackName = "Test";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = true;
		Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields> files = new LinkedHashMap<>();
		files.put(EDITABLE_PATH, new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(packBytes.length), "config", true, HashUtils.sha1(packBytes), "0"));
		group.files = files;
		fields.categories = Map.of("General", Map.of("main", group));
		PackDocument document = TestPacks.document(GroupManifestValidator.validate(fields));
		TestPacks.stageGeneration(storage, document);
		SelectedModpackTarget target = SelectedModpackTarget.prepare(document, null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
		new ClientSelectionStore(storage.selectionFile()).compareAndSet(document.manifest().modpackId(), null, target.selection().intent());
		storage.writeActiveState(document.manifest().modpackId(), document.contentToken(), document.ownershipLedger().toFields());
		return flatTarget(HashUtils.sha1(packBytes));
	}

	private static ModpackJsons.ModpackContentFields flatTarget(String sha1) {
		ModpackJsons.ModpackContentFields target = new ModpackJsons.ModpackContentFields(Set.of(
				new ModpackJsons.ModpackContentFields.ModpackContentItem(EDITABLE_PATH, 14, "config", true, sha1, "0")));
		target.modpackId = PACK_ID;
		target.contentToken = "1".repeat(40);
		return target;
	}

	private static Path write(Path path, byte[] bytes) throws Exception {
		Files.createDirectories(path.getParent());
		if (Files.exists(path) && ImmutableFiles.isProtected(path)) Files.delete(path);
		return Files.write(path, bytes);
	}
}
