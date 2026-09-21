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

/** The hconf-backed human-config store: claim-1 reads, reconcile saves, comment convergence, loud corruption. */
class HconfConfigsTest {

	@TempDir
	Path dir;

	private Path serverConfig() {
		return dir.resolve("server-config.hconf");
	}

	private Path legacyServerConfig() {
		return dir.resolve("server-config.json");
	}

	@Test
	void freshGenerationIsCanonicalWithCommentsAndBareGlobs() throws IOException {
		ServerConfigFieldsV3 config = HconfConfigs.readOrCreate(serverConfig(), ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.startsWith("# "), text);
		assertTrue(text.contains("# serve the modpack to clients from this server\nmodpackHost: true"), text);
		assertTrue(text.contains("syncedFiles: [mods/*.jar, kubejs/**, emotes/*]"), text);
		assertTrue(text.contains("name: \"\""), text);
		assertEquals("", config.modpack.name);
	}

	@Test
	void readsLegacyGsonJsonUnchanged() throws IOException {
		// the exact shape the Gson writer produced for years, including the old 2-space indentation
		String legacy = """
				{
				  "DO_NOT_CHANGE_IT": 2,
				  "modpackHost": true,
				  "syncedFiles": [
				    "/mods/*.jar",
				    "!/kubejs/server_scripts/**"
				  ],
				  "bindPort": 25590,
				  "connectionMode": "HOLEPUNCH",
				  "secretLifetime": 336
				}""";
		Files.write(legacyServerConfig(), legacy.getBytes(StandardCharsets.UTF_8));
		ServerConfigFieldsV3 config = HconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertEquals(2, config.DO_NOT_CHANGE_IT);
		assertEquals(25590, config.bindPort);
		assertEquals("", config.modpack.name);
	}

	@Test
	void saveKeepsBytesWhenModelMatches() throws IOException {
		HconfConfigs.readOrCreate(serverConfig(), ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		byte[] before = Files.readAllBytes(serverConfig());
		ServerConfigFieldsV3 config = HconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		HconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		byte[] after = Files.readAllBytes(serverConfig());
		// accepted-loaders seeding changes the model on first load; use the re-read model so nothing differs
		assertEquals(new String(before, StandardCharsets.UTF_8), new String(after, StandardCharsets.UTF_8));
	}

	@Test
	void saveSplicesOnlyTheChangedValueAndKeepsCommentsAndCrlf() throws IOException {
		String userFile = "# my server config\r\nmodpackHost: true # keep this note\r\nbindPort: 25565\r\n";
		Files.write(serverConfig(), userFile.getBytes(StandardCharsets.UTF_8));
		ServerConfigFieldsV3 config = HconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertTrue(config.modpackHost);
		assertTrue(Files.readString(serverConfig(), StandardCharsets.UTF_8).contains("modpackHost: true # keep this note"));
		assertEquals(25565, config.bindPort);
		config.bindPort = 25566;
		HconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("# my server config\r\n"), "banner comment lost:\n" + text);
		assertTrue(text.contains("modpackHost: true # keep this note\r\n"), "trailing comment lost:\n" + text);
		assertTrue(text.contains("bindPort: 25566\r\n"), "value not spliced:\n" + text);
		assertFalse(text.contains("25565"), "old value still present:\n" + text);
	}

	@Test
	void saveEnsuresMissingAnnotatedFieldsWithTheirComment() throws IOException {
		// an old file from before accept-proxy-protocol existed
		String oldFile = "modpackHost: false\r\nbindPort: 25565\r\n";
		Files.write(serverConfig(), oldFile.getBytes(StandardCharsets.UTF_8));
		ServerConfigFieldsV3 config = HconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertFalse(config.acceptProxyProtocol);
		HconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("# honor HAProxy PROXY protocol headers; enable only behind a trusted proxy\r\nacceptProxyProtocol: false"), text);
		assertTrue(text.contains("modpackHost: false"), "existing value disturbed:\n" + text);
	}

	@Test
	void saveIsIdempotent() throws IOException {
		ServerConfigFieldsV3 config = HconfConfigs.readOrCreate(serverConfig(), ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		config.bindPort = 25577;
		HconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		byte[] once = Files.readAllBytes(serverConfig());
		HconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		byte[] twice = Files.readAllBytes(serverConfig());
		assertEquals(new String(once, StandardCharsets.UTF_8), new String(twice, StandardCharsets.UTF_8));
	}

	@Test
	void corruptFileFailsLoudlyWithPositionOnReadAndSave() throws IOException {
		// two members glued on one line: adjacent values, positioned at the second key
		String corrupt = "modpackName: x  bindPort: 1\n";
		Files.write(serverConfig(), corrupt.getBytes(StandardCharsets.UTF_8));
		ConfigTools.ConfigParseException readError = assertThrows(ConfigTools.ConfigParseException.class, () -> HconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class));
		assertTrue(readError.getMessage().contains("line 1:17"), readError.getMessage());
		ConfigTools.ConfigParseException saveError = assertThrows(ConfigTools.ConfigParseException.class,
				() -> HconfConfigs.save(serverConfig(), new ServerConfigFieldsV3(), ServerConfigFieldsV3.class, ServerConfigFieldsV3::new));
		assertTrue(saveError.getMessage().contains("corrupt"), saveError.getMessage());
	}

	@Test
	void unknownKeysWarnButSurviveSaves() throws IOException {
		String withUnknown = "modpackName: X\nsomeFutureOption: true\n";
		Files.write(serverConfig(), withUnknown.getBytes(StandardCharsets.UTF_8));
		ServerConfigFieldsV3 config = HconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		HconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("someFutureOption: true"), "stale key not left alone:\n" + text);
	}

	@Test
	void saveRemovesArrayElementsTheModelDropped() throws IOException {
		// the model was read from this very file, so an element missing from it is a deliberate
		// removal - a normalized-away rule, a pin removed in the UI - and reconcile (insert-only,
		// §9.5) must not let it resurrect on the next read
		String userFile = """
				modpackName: "X"
				modpack: {
				  General: {
				    main: {
				      syncedFiles: ["mods/*.jar", "kubejs/**"]
				    }
				  }
				}
				""";
		Files.writeString(serverConfig(), userFile, StandardCharsets.UTF_8);
		ServerConfigFieldsV3 config = HconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		config.modpack.categories.get("General").get("main").syncedFiles = Set.of("mods/*.jar");
		HconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.contains("mods/*.jar"), "kept rule lost:\n" + text);
		assertFalse(text.contains("kubejs"), "removed rule resurrected:\n" + text);
		assertEquals(Set.of("mods/*.jar"),
				HconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow().modpack.categories.get("General").get("main").syncedFiles);
	}

	@Test
	void legacyJsonMigratesToHconfOnFirstSave() throws IOException {
		String legacy = "modpackHost: false\nbindPort: 25590\n";
		Files.write(legacyServerConfig(), legacy.getBytes(StandardCharsets.UTF_8));
		ServerConfigFieldsV3 config = HconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertFalse(config.modpackHost);
		HconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		assertFalse(Files.exists(legacyServerConfig()), "legacy .json not removed after migration");
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertTrue(text.startsWith("# "), "migrated file lacks the banner:\n" + text);
		assertTrue(text.contains("modpackHost: false"), "value not carried:\n" + text);
		assertTrue(text.contains("bindPort: 25590"), "value not carried:\n" + text);
		assertTrue(text.contains("# honor HAProxy PROXY protocol headers"), "fresh comments missing:\n" + text);
	}

	@Test
	void tombstonedFieldStaysOut() throws IOException {
		String disabled = "# honor HAProxy PROXY protocol headers; enable only behind a trusted proxy\n# acceptProxyProtocol: true\nmodpackName: X\n";
		Files.write(serverConfig(), disabled.getBytes(StandardCharsets.UTF_8));
		ServerConfigFieldsV3 config = HconfConfigs.read(serverConfig(), ServerConfigFieldsV3.class).orElseThrow();
		assertFalse(config.acceptProxyProtocol);
		HconfConfigs.save(serverConfig(), config, ServerConfigFieldsV3.class, ServerConfigFieldsV3::new);
		String text = Files.readString(serverConfig(), StandardCharsets.UTF_8);
		assertFalse(text.contains("\nacceptProxyProtocol:"), "ensure re-materialized a user-disabled key:\n" + text);
		assertTrue(text.contains("# acceptProxyProtocol: true"), "tombstone disturbed:\n" + text);
	}
}
