package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ServerConfigJsons.ServerConfigFieldsV3;
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
		assertTrue(text.contains("[modpack.groups.General.main]"), text);
		assertTrue(text.contains("from-server = [\"mods/*.jar\", \"kubejs/**\", \"emotes/*\"]"), text);
		assertTrue(text.contains("# extra paths from the server root"), text);
		assertTrue(text.contains("name = \"\""), text);
		assertTrue(text.contains("# REQUIRED is always installed. RECOMMENDED is pre-selected, players can opt out. OPTIONAL starts unchecked."), text);
		assertTrue(text.contains("selection = \"REQUIRED\""), text);
		assertTrue(text.contains("auto-exclude-server-side-mods = \"METADATA\""), text);
		assertTrue(text.contains("Use exclude for anything else."), text);
		assertFalse(text.contains("default-selected"), text);
		assertEquals("", config.modpack.name);
		assertEquals(ServerSideModExclusion.METADATA, config.modpack.categories.get("General").get("main").autoExcludeServerSideMods);
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
				.replaceAll("(?m)^[ \\t]*# extra paths from the server root\\R", "");
		assertFalse(withoutComments.contains("require a login-issued download secret"));
		assertFalse(withoutComments.contains("extra paths from the server root"));
		Files.writeString(serverConfig(), withoutComments, StandardCharsets.UTF_8);

		ServerConfigFieldsV3 config = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);

		String after = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertFalse(after.contains("require a login-issued download secret"), after);
		assertFalse(after.contains("extra paths from the server root"), after);
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
				[modpack.groups.General.main]
				from-server = ['mods/*.jar', 'kubejs/**']
				""";
		Files.writeString(serverConfig(), userFile, StandardCharsets.UTF_8);
		ServerConfigFieldsV3 config = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		config.modpack.categories.get("General").get("main").fromServer = Set.of("mods/*.jar");
		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("mods/*.jar"), "kept rule lost:\n" + text);
		assertFalse(text.contains("kubejs"), "removed rule resurrected:\n" + text);
		assertEquals(Set.of("mods/*.jar"),
				TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow().modpack.categories.get("General").get("main").fromServer);
	}

	@Test
	void humanEnumsForgiveCaseAndSaveCanonically() throws IOException {
		String mixed = """
				[network]
				connection-mode = "holepunch"

				[modpack.groups.General.main]
				selection = "recommended"
				auto-exclude-server-side-mods = "metadata"
				""";
		Files.writeString(serverConfig(), mixed, StandardCharsets.UTF_8);
		ServerConfigFieldsV3 config = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertEquals(ModpackConnectionMode.HOLEPUNCH, config.connectionMode);
		assertEquals(GroupSelectionMode.RECOMMENDED, config.modpack.categories.get("General").get("main").selection);
		assertEquals(ServerSideModExclusion.METADATA, config.modpack.categories.get("General").get("main").autoExcludeServerSideMods);
		assertEquals(mixed, Files.readString(serverConfig(), StandardCharsets.UTF_8));

		TomlConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("connection-mode = \"HOLEPUNCH\""), text);
		assertTrue(text.contains("selection = \"RECOMMENDED\""), text);
		assertTrue(text.contains("auto-exclude-server-side-mods = \"METADATA\""), text);
		assertFalse(text.contains("connection-mode = \"holepunch\""), text);
		assertFalse(text.contains("selection = \"recommended\""), text);
		assertFalse(text.contains("auto-exclude-server-side-mods = \"metadata\""), text);
	}

	@Test
	void unknownHumanEnumFailsLoudly() throws IOException {
		Files.writeString(serverConfig(), "[network]\nconnection-mode = \"holepunchs\"\n", StandardCharsets.UTF_8);
		ConfigTools.ConfigParseException error = assertThrows(ConfigTools.ConfigParseException.class, () -> TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class));
		assertTrue(error.getMessage().contains("holepunchs"), error.getMessage());
	}

	@Test
	void categoryNamedNameDoesNotCollideWithModpackName() throws IOException {
		String src = """
				[modpack]
				name = "My Pack"

				[modpack.groups.name.main]
				selection = "REQUIRED"
				""";
		Files.writeString(serverConfig(), src, StandardCharsets.UTF_8);
		ServerConfigFieldsV3 config = TomlConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertEquals("My Pack", config.modpack.name);
		assertEquals(GroupSelectionMode.REQUIRED, config.modpack.categories.get("name").get("main").selection);
	}
}
