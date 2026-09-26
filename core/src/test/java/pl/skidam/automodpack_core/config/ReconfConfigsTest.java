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

/** The reconf-backed human-config store: kebab keys, reconcile saves, comment convergence, loud corruption. */
class ReconfConfigsTest {

	@TempDir
	Path dir;

	private Path serverConfig() {
		return dir.resolve("server.conf");
	}

	@Test
	void freshGenerationIsCanonicalWithCommentsAndBareGlobs() throws IOException {
		ServerConfigFieldsV3 config = ReconfConfigs.readOrCreate(serverConfig(), ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.startsWith("# "), text);
		assertTrue(text.contains("from-server: [mods/*.jar, kubejs/**, emotes/*]"), text);
		assertTrue(text.contains("exclude: [**/.*, **/.*/**, \"**/*.{tmp,disabled,bak}\", kubejs/server_scripts/**]"), text);
		assertTrue(text.contains("# extra paths from the server root"), text);
		assertTrue(text.contains("name: \"\""), text);
		assertEquals("", config.modpack.name);
	}

	@Test
	void saveKeepsBytesWhenModelMatches() throws IOException {
		ReconfConfigs.readOrCreate(serverConfig(), ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		byte[] before = Files.readAllBytes(serverConfig());
		ServerConfigFieldsV3 config = ReconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		ReconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		byte[] after = Files.readAllBytes(serverConfig());
		assertEquals(new String(before, StandardCharsets.UTF_8), new String(after, StandardCharsets.UTF_8));
	}

	@Test
	void saveSplicesOnlyTheChangedValueAndKeepsCommentsAndCrlf() throws IOException {
		String userFile = "# my server config\r\nmodpack-host: true # keep this note\r\nbind-port: 25565\r\n";
		Files.write(serverConfig(), userFile.getBytes(StandardCharsets.UTF_8));
		ServerConfigFieldsV3 config = ReconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertTrue(config.modpackHost);
		assertTrue(Files.readString(serverConfig(), StandardCharsets.UTF_8).contains("modpack-host: true # keep this note"));
		assertEquals(25565, config.bindPort);
		config.bindPort = 25566;
		ReconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("# my server config\r\n"), "banner comment lost:\n" + text);
		assertTrue(text.contains("modpack-host: true # keep this note\r\n"), "trailing comment lost:\n" + text);
		assertTrue(text.contains("bind-port: 25566\r\n"), "value not spliced:\n" + text);
		assertFalse(text.contains("25565"), "old value still present:\n" + text);
	}

	@Test
	void saveEnsuresMissingAnnotatedFieldsWithTheirComment() throws IOException {
		String oldFile = "modpack-host: false\r\nbind-port: 25565\r\n";
		Files.write(serverConfig(), oldFile.getBytes(StandardCharsets.UTF_8));
		ServerConfigFieldsV3 config = ReconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertTrue(config.advertiseVersionsToSync);
		ReconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("# tell clients the pack Minecraft and loader versions\r\nadvertise-versions-to-sync: true"), text);
		assertTrue(text.contains("modpack-host: false"), "existing value disturbed:\n" + text);
	}

	@Test
	void saveIsIdempotent() throws IOException {
		ServerConfigFieldsV3 config = ReconfConfigs.readOrCreate(serverConfig(), ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		config.bindPort = 25577;
		ReconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		byte[] once = Files.readAllBytes(serverConfig());
		ReconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		byte[] twice = Files.readAllBytes(serverConfig());
		assertEquals(new String(once, StandardCharsets.UTF_8), new String(twice, StandardCharsets.UTF_8));
	}

	@Test
	void corruptFileFailsLoudlyWithPositionOnReadAndSave() throws IOException {
		String corrupt = "modpackName: x  bindPort: 1\n";
		Files.write(serverConfig(), corrupt.getBytes(StandardCharsets.UTF_8));
		ConfigTools.ConfigParseException readError = assertThrows(ConfigTools.ConfigParseException.class, () -> ReconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class));
		assertTrue(readError.getMessage().contains("line 1:17"), readError.getMessage());
		ConfigTools.ConfigParseException saveError = assertThrows(ConfigTools.ConfigParseException.class,
				() -> ReconfConfigs.save(serverConfig(), new ServerConfigFieldsV3(), ServerConfigFieldsV3.class, ServerConfigFieldsV3::new));
		assertTrue(saveError.getMessage().contains("corrupt"), saveError.getMessage());
	}

	@Test
	void unknownKeysWarnButSurviveSaves() throws IOException {
		String withUnknown = "modpackName: X\nsomeFutureOption: true\n";
		Files.write(serverConfig(), withUnknown.getBytes(StandardCharsets.UTF_8));
		ServerConfigFieldsV3 config = ReconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		ReconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("someFutureOption: true"), "stale key not left alone:\n" + text);
	}

	@Test
	void saveRemovesArrayElementsTheModelDropped() throws IOException {
		String userFile = """
				modpack: {
				  General: {
				    main: {
				      from-server: ["mods/*.jar", "kubejs/**"]
				    }
				  }
				}
				""";
		Files.writeString(serverConfig(), userFile, StandardCharsets.UTF_8);
		ServerConfigFieldsV3 config = ReconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		config.modpack.categories.get("General").get("main").fromServer = Set.of("mods/*.jar");
		ReconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("mods/*.jar"), "kept rule lost:\n" + text);
		assertFalse(text.contains("kubejs"), "removed rule resurrected:\n" + text);
		assertEquals(Set.of("mods/*.jar"),
				ReconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow().modpack.categories.get("General").get("main").fromServer);
	}

	@Test
	void tombstonedFieldStaysOut() throws IOException {
		String disabled = "# tell clients the pack Minecraft and loader versions\n# advertise-versions-to-sync: false\nmodpack-host: true\n";
		Files.write(serverConfig(), disabled.getBytes(StandardCharsets.UTF_8));
		ServerConfigFieldsV3 config = ReconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertTrue(config.advertiseVersionsToSync);
		ReconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertFalse(text.contains("\nadvertise-versions-to-sync:"), "ensure re-materialized a user-disabled key:\n" + text);
		assertTrue(text.contains("# advertise-versions-to-sync: false"), "tombstone disturbed:\n" + text);
	}

	@Test
	void colonLessObjectMembersParse() throws IOException {
		String text = """
				modpack-host: true
				modpack {
				  name: "Pack"
				  General {
				    main {
				      from-server: [mods/*.jar]
				    }
				  }
				}
				""";
		Files.writeString(serverConfig(), text, StandardCharsets.UTF_8);
		ServerConfigFieldsV3 config = ReconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertEquals("Pack", config.modpack.name);
		assertEquals(Set.of("mods/*.jar"), config.modpack.categories.get("General").get("main").fromServer);
	}
}
