package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.config.ReconfConfigs;
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
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.OfflineRepair;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Pinning is the player's escape hatch: repair never archives or removes a pinned jar, whether unowned or one the pack once shipped and deleted. */
class RepairPinnedModsTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void pinnedJarsSurviveRepairWhileUnpinnedStaleJarsAreArchived() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		byte[] kept = "kept".getBytes(StandardCharsets.UTF_8);
		byte[] pinnedDeleted = jar("sodium");
		byte[] staleDeleted = jar("oldlib");
		Map<String, byte[]> earlier = new LinkedHashMap<>();
		earlier.put("config/kept.toml", kept);
		earlier.put("mods/sodium.jar", pinnedDeleted);
		earlier.put("mods/oldlib.jar", staleDeleted);
		OwnershipLedger parent = TestPacks.document(manifest(earlier)).ownershipLedger();
		PackDocument document = TestPacks.document(manifest(Map.of("config/kept.toml", kept)), parent, TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		SelectedModpackTarget target = SelectedModpackTarget.prepare(document, null, new SelectionIntent(Set.of("main")), ClientPlatform.current());
		new ClientSelectionStore(storage.selectionFile()).put(document.manifest().modpackId(), target.selection().intent());
		storage.writeActiveState(document);
		write(storage.activePath("config/kept.toml"), kept);
		write(storage.gamePath("config/kept.toml"), kept);
		Path pinnedTombstoned = write(storage.gamePath("mods/sodium.jar"), pinnedDeleted);
		Path pinnedUnowned = write(storage.gamePath("mods/journeymap.jar"), jar("journeymap"));
		Path stale = write(storage.gamePath("mods/oldlib.jar"), staleDeleted);
		Path unpinned = write(storage.gamePath("mods/other.jar"), jar("other"));
		ClientConfigJsons.ClientConfigFieldsV3 config = new ClientConfigJsons.ClientConfigFieldsV3().withPinnedModIds(List.of("sodium", "journeymap"));
		ReconfConfigs.save(storage.clientConfigFile(), config, ClientConfigJsons.ClientConfigFieldsV3.class, ClientConfigJsons.ClientConfigFieldsV3::new);
		ClientOfflineRepair repair = new ClientOfflineRepair(storage, request -> {}, temporaryDirectory.resolve("automodpack.jar"));

		OfflineRepair.Prepared before = repair.inspect();
		OfflineRepair.Receipt receipt = repair.apply(before, Set.of(), Set.copyOf(before.unownedModPaths()));

		assertEquals(List.of("mods/oldlib.jar", "mods/other.jar"), before.unownedModPaths());
		assertEquals(2, receipt.archivedUnownedMods());
		assertTrue(Files.exists(pinnedTombstoned), "a pinned jar the pack once shipped and deleted stays");
		assertTrue(Files.exists(pinnedUnowned), "a pinned unowned jar stays with the archive box on");
		assertFalse(Files.exists(stale));
		assertFalse(Files.exists(unpinned));
		assertTrue(Files.exists(storage.objectFile(HashUtils.sha1(staleDeleted))), "archived jars stay recoverable");
	}

	private static byte[] jar(String modId) throws Exception {
		Path file = Files.createTempFile("mod-", ".jar");
		try (OutputStream output = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(output)) {
			jar.putNextEntry(new JarEntry("fabric.mod.json"));
			jar.write(("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0.0\"}").getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();
		}
		byte[] bytes = Files.readAllBytes(file);
		Files.delete(file);
		return bytes;
	}

	private static GroupManifest manifest(Map<String, byte[]> contents) {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.modpackName = "Test";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = true;
		group.defaultSelected = true;
		Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields> files = new LinkedHashMap<>();
		contents.forEach((path, bytes) -> files.put(path, new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(bytes.length), false, HashUtils.sha1(bytes), "0")));
		group.files = files;
		fields.categories = Map.of("General", Map.of("main", group));
		return GroupManifestValidator.validate(fields);
	}

	private static Path write(Path path, byte[] bytes) throws Exception {
		Files.createDirectories(path.getParent());
		return Files.write(path, bytes);
	}
}
