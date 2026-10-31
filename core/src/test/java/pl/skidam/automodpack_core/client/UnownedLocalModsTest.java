package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.loader.ModFileCache;
import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/** The pack-change review only asks about the local mods the switch actually leaves behind. */
class UnownedLocalModsTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void aPackChangeListsLocalJarsItWouldLeaveBesideTheNewPack() throws Exception {
		ClientStorage storage = storage();
		byte[] local = "local-mod".getBytes(StandardCharsets.UTF_8);
		Files.write(storage.gamePath("mods/local.jar"), local);
		ClientObjectStore.storeObject(storage, HashUtils.sha1(local), local);

		PackDocument installed = TestPacks.document(TestPacks.manifest("installed", "config/installed.txt", "installed"));
		storeContent(storage, "installed");
		TestPacks.stageGeneration(storage, installed);
		storage.writeActiveState(installed);
		new ClientSelectionStore(storage.selectionFile()).put(installed.manifest().modpackId(), new SelectionIntent(Set.of("main")));

		GroupManifest replacementManifest = pack("def5678", "config/replacement.txt", "replacement");
		PackDocument replacement = PackDocument.create(replacementManifest, TestPacks.policySha1(replacementManifest), TestPacks.CREATED, null);
		storeContent(storage, "replacement");
		TestPacks.stageGeneration(storage, replacement);

		ClientUpdatePlanBuilder builder = builder(storage);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory()); ModFileCache modCache = ModFileCache.open(storage.modCacheDirectory())) {
			SelectedModpackTarget target = SelectedModpackTarget.prepare(replacement, null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
			ClientUpdatePlanBuilder.PreparedPlan switchPlan = builder.buildPlan(new ClientUpdatePlanBuilder.Input(target, null, new ClientConfigJsons.ClientConfigFieldsV3(), true),
					ClientProjectionView.observe(storage), cache, modCache);
			assertEquals(List.of("mods/local.jar"), List.copyOf(switchPlan.unownedLocalMods().keySet()));

			SelectedModpackTarget samePack = SelectedModpackTarget.prepare(installed, null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
			ClientUpdatePlanBuilder.PreparedPlan updatePlan = builder.buildPlan(new ClientUpdatePlanBuilder.Input(samePack, null, new ClientConfigJsons.ClientConfigFieldsV3(), true),
					ClientProjectionView.observe(storage), cache, modCache);
			assertTrue(updatePlan.unownedLocalMods().isEmpty());
		}
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private static void storeContent(ClientStorage storage, String content) throws Exception {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		ClientObjectStore.storeObject(storage, HashUtils.sha1(bytes), bytes);
	}

	private static GroupManifest pack(String modpackId, String path, String content) {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(bytes.length, false, HashUtils.sha1(bytes), null);
		GroupManifest.Group group = new GroupManifest.Group("", modpackId, "General", GroupSelectionMode.OPTIONAL, Set.of(), Set.of(), Set.of(), Map.of(path, file));
		return new GroupManifest(modpackId, modpackId, "", "", "", "", Map.of("main", group));
	}

	private static ClientUpdatePlanBuilder builder(ClientStorage storage) {
		return new ClientUpdatePlanBuilder(storage, new ModpackLoaderService() {
			@Override
			public void loadModpack(ModpackLoadRequest request) {}
		}, "fabric");
	}
}
