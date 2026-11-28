package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ServerConfigJsons.ServerConfigFieldsV3;
import pl.skidam.automodpack_core.modpack.group.CategoryPickMode;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;
import pl.skidam.automodpack_core.modpack.group.ServerSideModExclusion;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

/** The toml-edit-backed human-config store: structured TOML tables, lossless reconcile saves, comment convergence, loud corruption. */
class TomlConfigsTest {

	@TempDir
	Path dir;

	private Path serverConfig() {
		return dir.resolve("server.toml");
	}

	@Test
	void freshGenerationIsCanonicalTomlWithTablesCommentsAndLiteralGlobs() throws IOException {
		ServerConfigFieldsV3 config = TomlConfigs.readOrCreate(serverConfig(), ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.startsWith("# "), text);
		assertTrue(text.contains("[network]"), text);
		assertTrue(text.contains("[secrets]"), text);
		assertTrue(text.contains("[nag]"), text);
		assertTrue(text.contains("[export-http]"), text);
		assertTrue(text.contains("[modpack]"), text);
		assertTrue(text.contains("[groups.main]"), text);
		assertFalse(text.contains("[modpack.groups"), text);
		assertFalse(text.contains("[categories"), text);
		assertTrue(text.contains("from-server = [\"mods/*.jar\", \"kubejs/**\", \"emotes/*\"]"), text);
		assertFalse(text.contains("name = \"Core\""), "the main group must not ship a name the admin never wrote:\n" + text);
		assertTrue(text.contains("selection = \"REQUIRED\""), text);
		assertTrue(text.indexOf("[modpack]") < text.indexOf("[groups.main]") && text.indexOf("[groups.main]") < text.indexOf("[network]"),
				"sections must run [modpack], [groups.*], then [network]:\n" + text);
		assertTrue(text.contains("auto-exclude-server-side-mods = \"METADATA\""), text);
		assertFalse(text.contains("default-selected"), text);
		assertEquals("", config.modpack.name);
		assertEquals("", config.modpack.groups.get("main").name);
		assertEquals(ServerSideModExclusion.METADATA, config.modpack.autoExcludeServerSideMods);
		assertNull(config.modpack.groups.get("main").autoExcludeServerSideMods);
	}

	@Test
	void saveKeepsBytesWhenModelMatches() throws IOException {
		TomlConfigs.readOrCreate(serverConfig(), ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		byte[] before = Files.readAllBytes(serverConfig());
		ServerConfigFieldsV3 config = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		byte[] after = Files.readAllBytes(serverConfig());
		assertEquals(new String(before, StandardCharsets.UTF_8), new String(after, StandardCharsets.UTF_8));
	}

	@Test
	void saveSplicesOnlyTheChangedValueAndKeepsCommentsAndCrlf() throws IOException {
		String userFile = "# my server config\r\nmodpack-host = true # keep this note\r\n\r\n[network]\r\nbind-port = 25565\r\n";
		Files.write(serverConfig(), userFile.getBytes(StandardCharsets.UTF_8));
		ServerConfigFieldsV3 config = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertTrue(config.modpackHost);
		assertEquals(25565, config.bindPort);
		config.bindPort = 25566;
		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("# my server config\r\n"), "banner comment lost:\n" + text);
		assertTrue(text.contains("modpack-host = true # keep this note\r\n"), "trailing comment lost:\n" + text);
		assertTrue(text.contains("bind-port = 25566\r\n"), "value not spliced:\n" + text);
		assertFalse(text.contains("25565"), "old value still present:\n" + text);
	}

	@Test
	void saveEnsuresMissingDeclaredFieldsWithTheirComment() throws IOException {
		String oldFile = "modpack-host = false\r\n\r\n[network]\r\nbind-port = 25565\r\n";
		Files.write(serverConfig(), oldFile.getBytes(StandardCharsets.UTF_8));
		ServerConfigFieldsV3 config = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertTrue(config.advertiseVersionsToSync);
		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("# tell clients the pack Minecraft and loader versions\r\nadvertise-versions-to-sync = true"), text);
		assertTrue(text.contains("modpack-host = false"), "existing value disturbed:\n" + text);
	}

	@Test
	void saveDoesNotRestoreDeletedCommentsOnExistingMembers() throws IOException {
		TomlConfigs.readOrCreate(serverConfig(), ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		String withoutComments = text
				.replaceAll("(?m)^[ \\t]*# require a login-issued download secret\\R", "")
				.replaceAll("(?m)^[ \\t]*# host-modpack/<id>/ is included in full.*\\R", "");
		assertFalse(withoutComments.contains("require a login-issued download secret"));
		assertFalse(withoutComments.contains("host-modpack/<id>/ is included in full"));
		Files.writeString(serverConfig(), withoutComments, StandardCharsets.UTF_8);

		ServerConfigFieldsV3 config = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);

		String after = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertFalse(after.contains("require a login-issued download secret"), after);
		assertFalse(after.contains("host-modpack/<id>/ is included in full"), after);
		assertTrue(after.contains("validate = true"), after);
		assertTrue(after.contains("from-server ="), after);
	}

	@Test
	void saveIsIdempotent() throws IOException {
		ServerConfigFieldsV3 config = TomlConfigs.readOrCreate(serverConfig(), ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		config.bindPort = 25577;
		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		byte[] once = Files.readAllBytes(serverConfig());
		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		byte[] twice = Files.readAllBytes(serverConfig());
		assertEquals(new String(once, StandardCharsets.UTF_8), new String(twice, StandardCharsets.UTF_8));
	}

	@Test
	void corruptFileFailsLoudlyWithPositionOnReadAndSave() throws IOException {
		String corrupt = "modpack-host = true  bind-port = 1\n";
		Files.write(serverConfig(), corrupt.getBytes(StandardCharsets.UTF_8));
		ConfigTools.ConfigParseException readError = assertThrows(ConfigTools.ConfigParseException.class, () -> TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class));
		assertTrue(readError.getMessage().contains("line 1:"), readError.getMessage());
		ConfigTools.ConfigParseException saveError = assertThrows(ConfigTools.ConfigParseException.class,
				() -> TomlConfigs.save(serverConfig(), new ServerConfigFieldsV3(), ServerConfigFieldsV3.class, ServerConfigFieldsV3::new));
		assertTrue(saveError.getMessage().contains("corrupt"), saveError.getMessage());
	}

	@Test
	void unknownKeysWarnButSurviveSaves() throws IOException {
		String withUnknown = "modpack-host = true\nsome-future-option = true\n";
		Files.write(serverConfig(), withUnknown.getBytes(StandardCharsets.UTF_8));
		ServerConfigFieldsV3 config = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("some-future-option = true"), "stale key not left alone:\n" + text);
	}

	@Test
	void saveRemovesArrayElementsTheModelDropped() throws IOException {
		String userFile = """
				[groups.main]
				from-server = ['mods/*.jar', 'kubejs/**']
				""";
		Files.writeString(serverConfig(), userFile, StandardCharsets.UTF_8);
		ServerConfigFieldsV3 config = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		config.modpack.groups.get("main").fromServer = Set.of("mods/*.jar");
		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("mods/*.jar"), "kept rule lost:\n" + text);
		assertFalse(text.contains("kubejs/**"), "removed rule resurrected:\n" + text);
		assertEquals(Set.of("mods/*.jar"),
				TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow().modpack.groups.get("main").fromServer);
	}

	@Test
	void humanEnumsForgiveCaseAndSaveCanonically() throws IOException {
		String mixed = """
				[network]
				connection-mode = "holepunch"

				[modpack]
				auto-exclude-server-side-mods = "platforms"

				[categories.shaders]
				pick = "at_most_one"

				[groups.main]
				category = "shaders"
				selection = "recommended"
				auto-exclude-server-side-mods = "metadata"
				""";
		Files.writeString(serverConfig(), mixed, StandardCharsets.UTF_8);
		ServerConfigFieldsV3 config = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertEquals(ModpackConnectionMode.HOLEPUNCH, config.connectionMode);
		assertEquals(ServerSideModExclusion.PLATFORMS, config.modpack.autoExcludeServerSideMods);
		assertEquals(CategoryPickMode.AT_MOST_ONE, config.modpack.categories.get("shaders").pick);
		assertEquals(GroupSelectionMode.RECOMMENDED, config.modpack.groups.get("main").selection);
		assertEquals(ServerSideModExclusion.METADATA, config.modpack.groups.get("main").autoExcludeServerSideMods);
		assertEquals(mixed, Files.readString(serverConfig(), StandardCharsets.UTF_8));

		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("connection-mode = \"HOLEPUNCH\""), text);
		assertTrue(text.contains("pick = \"AT_MOST_ONE\""), text);
		assertTrue(text.contains("selection = \"RECOMMENDED\""), text);
		assertTrue(text.contains("auto-exclude-server-side-mods = \"PLATFORMS\""), text);
		assertTrue(text.contains("auto-exclude-server-side-mods = \"METADATA\""), text);
		assertFalse(text.contains("\"holepunch\""), text);
		assertFalse(text.contains("\"at_most_one\""), text);
		assertFalse(text.contains("\"recommended\""), text);
		assertFalse(text.contains("\"platforms\""), text);
		assertFalse(text.contains("\"metadata\""), text);
	}

	@Test
	void unknownHumanEnumFailsLoudly() throws IOException {
		Files.writeString(serverConfig(), "[network]\nconnection-mode = \"holepunchs\"\n", StandardCharsets.UTF_8);
		ConfigTools.ConfigParseException error = assertThrows(ConfigTools.ConfigParseException.class, () -> TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class));
		assertTrue(error.getMessage().contains("holepunchs"), error.getMessage());
	}

	@Test
	void categoryAndGroupTablesRoundTripSparselyWithoutBloatingOptionalKeys() throws IOException {
		ServerConfigFieldsV3 config = new ServerConfigFieldsV3();
		ServerConfigJsons.CategoryDeclaration shaders = new ServerConfigJsons.CategoryDeclaration();
		shaders.name = "Shaders";
		shaders.pick = CategoryPickMode.AT_MOST_ONE;
		config.modpack.categories.put("shaders", shaders);
		ServerConfigJsons.GroupDeclaration bsl = new ServerConfigJsons.GroupDeclaration();
		bsl.name = "BSL Shaders";
		bsl.category = "shaders";
		bsl.selection = GroupSelectionMode.RECOMMENDED;
		config.modpack.groups.put("bsl", bsl);

		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("[categories.shaders]"), text);
		assertTrue(text.contains("[groups.bsl]"), text);
		assertFalse(text.contains("[groups.bsl]\nname = \"BSL Shaders\"\ndescription"), "blank description should stay omitted sparsely:\n" + text);
		ServerConfigFieldsV3 reread = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertEquals("Shaders", reread.modpack.categories.get("shaders").name);
		assertEquals(CategoryPickMode.AT_MOST_ONE, reread.modpack.categories.get("shaders").pick);
		assertEquals(GroupSelectionMode.RECOMMENDED, reread.modpack.groups.get("bsl").selection);
		assertNull(reread.modpack.groups.get("bsl").autoExcludeServerSideMods);
	}

	@Test
	void groupConflictsRoundTripAndStayOmittedWhenEmpty() throws IOException {
		ServerConfigFieldsV3 config = new ServerConfigFieldsV3();
		ServerConfigJsons.GroupDeclaration audio = new ServerConfigJsons.GroupDeclaration();
		audio.selection = GroupSelectionMode.OPTIONAL;
		audio.conflicts = Set.of("zoom");
		config.modpack.groups.put("audio", audio);

		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("conflicts = [\"zoom\"]"), text);
		assertFalse(text.contains("[groups.main]\nselection = \"REQUIRED\"\nconflicts"), "an empty conflict list must stay omitted:\n" + text);
		ServerConfigFieldsV3 reread = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertEquals(Set.of("zoom"), reread.modpack.groups.get("audio").conflicts);
		assertTrue(reread.modpack.groups.get("main").conflicts.isEmpty());
	}

	@Test
	void insertImportedGroupsAppendsNewGroupsLosslesslyWithoutTouchingExistingTables() throws IOException {
		String existing = "# header comment\r\nmodpack-host = true\r\n\r\n[groups.main]\r\nselection = \"REQUIRED\" # keep inline\r\n";
		Files.writeString(serverConfig(), existing, StandardCharsets.UTF_8);
		Map<String, ServerConfigJsons.GroupDeclaration> imported = new LinkedHashMap<>();
		ServerConfigJsons.GroupDeclaration extras = new ServerConfigJsons.GroupDeclaration();
		extras.selection = GroupSelectionMode.OPTIONAL;
		imported.put("extras", extras);
		imported.put("main", new ServerConfigJsons.GroupDeclaration());

		TomlConfigs.insertImportedGroups(serverConfig(), imported);
		String after = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(after.contains("# header comment\r\n"), after);
		assertTrue(after.contains("selection = \"REQUIRED\" # keep inline"), after);
		assertTrue(after.contains("[groups.extras]"), after);
		ServerConfigFieldsV3 reread = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertEquals(GroupSelectionMode.REQUIRED, reread.modpack.groups.get("main").selection, "existing main must not be overwritten");
		assertEquals(GroupSelectionMode.OPTIONAL, reread.modpack.groups.get("extras").selection);
	}

	@Test
	void saveSynchronizesAddedUpdatedAndRemovedCategoriesAndGroupsWhileKeepingComments() throws IOException {
		String initial = """
				# keep banner
				modpack-host = true

				[categories.shaders]
				name = "Shaders" # keep category note
				pick = "AT_MOST_ONE"

				[categories.obsolete]
				name = "Obsolete"

				[groups.main]
				selection = "REQUIRED" # keep group note

				[groups.legacy]
				selection = "OPTIONAL"
				""";
		Files.writeString(serverConfig(), initial, StandardCharsets.UTF_8);
		ServerConfigFieldsV3 config = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		config.modpack.categories.remove("obsolete");
		config.modpack.groups.remove("legacy");
		ServerConfigJsons.CategoryDeclaration lighting = new ServerConfigJsons.CategoryDeclaration();
		lighting.name = "Lighting";
		lighting.pick = CategoryPickMode.EXACTLY_ONE;
		config.modpack.categories.put("lighting", lighting);
		ServerConfigJsons.GroupDeclaration iris = new ServerConfigJsons.GroupDeclaration();
		iris.name = "Iris";
		iris.category = "shaders";
		iris.selection = GroupSelectionMode.RECOMMENDED;
		config.modpack.groups.put("iris", iris);

		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String after = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(after.contains("# keep banner"), after);
		assertTrue(after.contains("name = \"Shaders\" # keep category note"), after);
		assertTrue(after.contains("selection = \"REQUIRED\" # keep group note"), after);
		assertTrue(after.contains("[categories.lighting]"), after);
		assertTrue(after.contains("[groups.iris]"), after);
		assertFalse(after.contains("[categories.obsolete]"), after);
		assertFalse(after.contains("[groups.legacy]"), after);

		ServerConfigFieldsV3 reread = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertEquals(CategoryPickMode.EXACTLY_ONE, reread.modpack.categories.get("lighting").pick);
		assertEquals(GroupSelectionMode.RECOMMENDED, reread.modpack.groups.get("iris").selection);
		assertFalse(reread.modpack.categories.containsKey("obsolete"));
		assertFalse(reread.modpack.groups.containsKey("legacy"));
	}
}
