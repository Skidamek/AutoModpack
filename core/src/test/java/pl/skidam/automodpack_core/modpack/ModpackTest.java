package pl.skidam.automodpack_core.modpack;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.Constants.DEBUG;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.ModpackContentTools;

class ModpackTest {

	@TempDir
	Path testFilesDir;
	Path originalContentFile;

	@BeforeEach
	void setUp() throws IOException {
		DEBUG = true;
		originalContentFile = Constants.hostModpackContentFile;
		Constants.hostModpackContentFile = testFilesDir.getParent().resolve("automodpack-content.json");
		createTestFiles();
	}

	@AfterEach
	void tearDown() {
		Constants.hostModpackContentFile = originalContentFile;
	}

	private void createTestFiles() throws IOException {
		// Root level files
		Files.writeString(testFilesDir.resolve("file.txt"), "a");

		// Config directory
		Path configDir = testFilesDir.resolve("config");
		Files.createDirectories(configDir);
		Files.writeString(configDir.resolve("config.json"), "a");
		Files.writeString(configDir.resolve("config-mod.json5"), "a");
		Files.writeString(configDir.resolve("mod-config.toml"), "a");
		Files.writeString(configDir.resolve("random-options.txt"), "a");

		// Mods directory
		Path modsDir = testFilesDir.resolve("mods");
		Files.createDirectories(modsDir);
		Files.writeString(modsDir.resolve("mod-1.20.jar"), "a");
		Files.writeString(modsDir.resolve("mod-1.19.jar"), "a");
		Files.writeString(modsDir.resolve("client-mod-1.20.jar"), "a");
		Files.writeString(modsDir.resolve("client-mod-1.19.jar"), "a");
		Files.writeString(modsDir.resolve("server-mod-1.20.jar"), "a");
		Files.writeString(modsDir.resolve("server-mod-1.19.jar"), "a");
		Files.writeString(modsDir.resolve("mod"), "a");

		// Mods subdirectory
		Path modsRandomDir = modsDir.resolve("random directory");
		Files.createDirectories(modsRandomDir);
		Files.writeString(modsRandomDir.resolve("random-config.yaml"), "a");

		// Shaders directory
		Path shadersDir = testFilesDir.resolve("shaders");
		Files.createDirectories(shadersDir);
		Files.writeString(shadersDir.resolve("shader1.zip"), "a");
		Files.writeString(shadersDir.resolve("shader2.zip"), "a");
		Files.writeString(shadersDir.resolve("shader3.zip"), "a");
		Files.writeString(shadersDir.resolve("notashader.zip"), "a");
		Files.writeString(shadersDir.resolve("shaderconfig.txt"), "a");
	}

	@Test
	void assignsFilesFromPerGroupDirectories() throws IOException {
		Constants.serverConfig = new Jsons.ServerConfigFieldsV3();
		Constants.serverConfig.autoExcludeUnnecessaryFiles = false;

		// Mirrors a real server: host-modpack/main is the modpack dir and host-modpack/<group>
		// directories sit beside it.
		Path hostModpack = testFilesDir.resolve("host-modpack");
		Path mainDir = hostModpack.resolve("main");
		Path extrasDir = hostModpack.resolve("animatedEntities");
		Files.createDirectories(mainDir.resolve("mods"));
		Files.createDirectories(extrasDir.resolve("mods"));
		Files.createDirectories(extrasDir.resolve("resourcepacks"));
		Files.writeString(mainDir.resolve("mods/core.jar"), "a");
		Files.writeString(extrasDir.resolve("mods/entity_texture_features.jar"), "a");
		Files.writeString(extrasDir.resolve("resourcepacks/FreshAnimations.zip"), "a");

		Jsons.GroupDeclaration main = new Jsons.GroupDeclaration();
		main.required = true;
		Jsons.GroupDeclaration extras = new Jsons.GroupDeclaration();
		extras.displayName = "Animated Entities";
		extras.recommended = true;

		Map<String, Jsons.GroupDeclaration> groups = new LinkedHashMap<>();
		groups.put("main", main);
		groups.put("animatedEntities", extras);

		ModpackContent content = new ModpackContent("GroupDirPack", null, mainDir, groups, new ModpackExecutor().getExecutor());
		assertTrue(content.create(null));

		Jsons.ModpackContentFields manifest = ModpackContentTools.read(Constants.hostModpackContentFile);
		assertNotNull(manifest);

		// Files in a group directory belong to that group even with no globs configured at all.
		assertEquals(Set.of("/mods/entity_texture_features.jar", "/resourcepacks/FreshAnimations.zip"),
				manifest.groups.get("animatedEntities").files);
		assertEquals(Set.of("/mods/core.jar"), manifest.groups.get("main").files);

		// Paths are relative to each group's own directory, so they install to the same place.
		assertTrue(manifest.list.stream().anyMatch(fileItem -> fileItem.file.equals("/mods/entity_texture_features.jar")));
	}

	@Test
	void assignsFilesToDeclaredGroups() {
		Constants.serverConfig = new Jsons.ServerConfigFieldsV3();
		Constants.serverConfig.autoExcludeUnnecessaryFiles = false;

		Jsons.GroupDeclaration shaders = new Jsons.GroupDeclaration();
		shaders.displayName = "Shaders";
		shaders.syncedFiles = Set.of("/shaders/**");

		Jsons.GroupDeclaration configs = new Jsons.GroupDeclaration();
		configs.displayName = "Configs";
		configs.recommended = true;
		configs.syncedFiles = Set.of("/config/**");

		Jsons.GroupDeclaration core = new Jsons.GroupDeclaration();
		core.displayName = "Core";
		core.required = true;

		// LinkedHashMap: declaration order decides which group claims a path first.
		Map<String, Jsons.GroupDeclaration> groups = new LinkedHashMap<>();
		groups.put("shaders", shaders);
		groups.put("configs", configs);
		groups.put("core", core);

		ModpackContent content = new ModpackContent("GroupPack", null, testFilesDir, groups, new ModpackExecutor().getExecutor());
		assertTrue(content.create(null));

		Jsons.ModpackContentFields manifest = ModpackContentTools.read(Constants.hostModpackContentFile);
		assertNotNull(manifest);
		assertEquals(Set.of("shaders", "configs", "core"), manifest.groups.keySet());

		// Metadata survives the round trip to the manifest the client will read.
		assertTrue(manifest.groups.get("core").required);
		assertFalse(manifest.groups.get("shaders").required);
		assertTrue(manifest.groups.get("configs").recommended);

		assertTrue(manifest.groups.get("shaders").files.stream().allMatch(file -> file.startsWith("/shaders/")));
		assertTrue(manifest.groups.get("shaders").files.contains("/shaders/shader1.zip"));
		assertTrue(manifest.groups.get("configs").files.contains("/config/config.json"));

		// Nothing matched the required group's (empty) globs, so it collects the leftovers.
		assertTrue(manifest.groups.get("core").files.contains("/mods/mod-1.20.jar"));
		assertTrue(manifest.groups.get("core").files.contains("/file.txt"));

		// Every generated file belongs to exactly one group, and the groups cover the whole list.
		List<String> grouped = manifest.groups.values().stream().flatMap(group -> group.files.stream()).toList();
		assertEquals(grouped.size(), Set.copyOf(grouped).size(), "a file was claimed by more than one group");
		assertEquals(manifest.list.stream().map(item -> item.file).collect(Collectors.toSet()), Set.copyOf(grouped));
	}

	@Test
	void modpackTest() {
		// Use relative paths for editable rules (relative to testFilesDir)
		var editable = List.of("/file.txt", "/config/*", "!/config/config-mod.json5");

		editable.forEach(System.out::println);

		var correctResults = List.of(
				"ModpackContentItems(file=/shaders/notashader.zip, size=1, type=other, editable=false, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/config/config-mod.json5, size=1, type=config, editable=false, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/mods/random directory/random-config.yaml, size=1, type=other, editable=false, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/file.txt, size=1, type=other, editable=true, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/shaders/shader1.zip, size=1, type=other, editable=false, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/config/config.json, size=1, type=config, editable=true, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/mods/client-mod-1.19.jar, size=1, type=other, editable=false, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/shaders/shader2.zip, size=1, type=other, editable=false, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/config/mod-config.toml, size=1, type=config, editable=true, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/shaders/shader3.zip, size=1, type=other, editable=false, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/mods/client-mod-1.20.jar, size=1, type=other, editable=false, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/shaders/shaderconfig.txt, size=1, type=other, editable=false, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/mods/mod, size=1, type=other, editable=false, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/config/random-options.txt, size=1, type=config, editable=true, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/mods/mod-1.19.jar, size=1, type=other, editable=false, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/mods/mod-1.20.jar, size=1, type=other, editable=false, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/mods/server-mod-1.19.jar, size=1, type=other, editable=false, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
				"ModpackContentItems(file=/mods/server-mod-1.20.jar, size=1, type=other, editable=false, forceCopy=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)");

		Constants.serverConfig = new Jsons.ServerConfigFieldsV3();
		Constants.serverConfig.autoExcludeUnnecessaryFiles = false;

		ModpackContent content = new ModpackContent("TestPack", null, testFilesDir, new HashSet<>(), new HashSet<>(editable),
				Set.of("/config/random-options.txt"), new HashSet<>(),
				new ModpackExecutor().getExecutor());
		content.create(null);
		Jsons.ModpackContentFields firstManifest = ModpackContentTools.read(Constants.hostModpackContentFile);
		assertNotNull(firstManifest);
		assertTrue(ModpackId.isValid(firstManifest.modpackId));
		assertTrue(firstManifest.list.stream().filter(item -> item.file.equals("/config/random-options.txt")).findFirst().orElseThrow().overwriteEditable);
		assertFalse(firstManifest.list.stream().filter(item -> item.file.equals("/config/config.json")).findFirst().orElseThrow().overwriteEditable);

		ModpackContent renamedContent = new ModpackContent("Renamed Pack", null, testFilesDir, new HashSet<>(), new HashSet<>(editable),
				Set.of("/config/random-options.txt"), new HashSet<>(),
				new ModpackExecutor().getExecutor());
		renamedContent.create(null);
		Jsons.ModpackContentFields renamedManifest = ModpackContentTools.read(Constants.hostModpackContentFile);
		assertNotNull(renamedManifest);
		assertEquals(firstManifest.modpackId, renamedManifest.modpackId);
		assertEquals("Renamed Pack", renamedManifest.modpackName);

		boolean correct = true;

		System.out.println();

		if (content.list.size() != correctResults.size()) {
			System.out.println("Incorrect number of items! Expected " + correctResults.size() + " but got " + content.list.size());
			correct = false;
		}

		for (var item : content.list) {
			if (correctResults.contains(item.toString())) {
				System.out.println("Correct: " + item);
			} else {
				System.out.println("Incorrect: " + item);
				correct = false;
				break;
			}
		}

		assertTrue(correct);
	}

}
