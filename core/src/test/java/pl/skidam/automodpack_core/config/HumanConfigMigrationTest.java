package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;

import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;
import pl.skidam.automodpack_core.modpack.group.ServerSideModExclusion;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

class HumanConfigMigrationTest {

	@TempDir
	Path dir;

	@Test
	void migratesV4ServerJsonOnReadAndStopsUsingTheBackup() throws Exception {
		Path toml = dir.resolve("server.toml");
		Path json = dir.resolve("automodpack-server.json");
		Files.writeString(json, """
				{
				  "DO_NOT_CHANGE_IT": 2,
				  "modpackName": "My Pack",
				  "syncedFiles": ["/mods/*.jar", "/kubejs/**", "!/kubejs/server_scripts/**"],
				  "allowEditsInFiles": ["/options.txt", "/config/**"],
				  "requireAutoModpackOnClient": true,
				  "addressToSend": "play.example.com",
				  "portToSend": 25565,
				  "requireMagicPackets": false,
				  "bindPort": -1,
				  "nagUnModdedClients": true,
				  "nagMessage": "install me"
				}
				""", StandardCharsets.UTF_8);

		ServerConfigJsons.ServerConfigFieldsV3 config = TomlConfigs.read(toml, ServerConfigJsons.ServerConfigFieldsV3.class).orElseThrow();
		assertEquals("My Pack", config.modpack.name);
		assertEquals(Set.of("mods/*.jar", "kubejs/**", "!kubejs/server_scripts/**"), config.modpack.categories.get("General").get("main").fromServer);
		assertTrue(config.modpack.categories.get("General").get("main").fromServer.contains("!kubejs/server_scripts/**"));
		assertEquals(Set.of("options.txt", "config/**"), config.modpack.categories.get("General").get("main").editable);
		assertTrue(config.requireModpack);
		assertEquals("play.example.com", config.advertisedEndpointHost);
		assertEquals(ModpackConnectionMode.HOLEPUNCH, config.connectionMode);
		assertTrue(config.modpack.categories.get("General").get("main").exclude.contains("**/.*"));
		assertFalse(config.modpack.categories.get("General").get("main").exclude.contains("kubejs/server_scripts/**"));
		assertEquals(GroupSelectionMode.REQUIRED, config.modpack.categories.get("General").get("main").selection, "the migrated core group must stay required");
		assertEquals(ServerSideModExclusion.METADATA, config.modpack.categories.get("General").get("main").autoExcludeServerSideMods);
		assertTrue(Files.isRegularFile(toml));
		assertTrue(Files.isRegularFile(dir.resolve("automodpack-server.json.backup")));
		assertFalse(Files.exists(json));

		String text = Files.readString(toml, StandardCharsets.UTF_8);
		assertTrue(text.contains("from-server ="), text);
		assertTrue(text.contains("!kubejs/server_scripts/**"), text);

		Files.writeString(json, "{\"modpackName\":\"ignored\"}", StandardCharsets.UTF_8);
		ServerConfigJsons.ServerConfigFieldsV3 second = TomlConfigs.read(toml, ServerConfigJsons.ServerConfigFieldsV3.class).orElseThrow();
		assertEquals("My Pack", second.modpack.name);
	}

	@Test
	void migratesV5RcServerConfToTomlAndBacksUpConf() throws Exception {
		Path toml = dir.resolve("server.toml");
		Path conf = dir.resolve("server.conf");
		Files.writeString(conf, """
				# v5-rc server config
				modpack-host: true
				bind-port: 25570
				advertised-endpoint-host: "play.example.org"
				connection-mode: http
				validate-secrets: false
				auto-exclude-server-side-mods: false
				accepted-loaders: [fabric, neoforge]
				modpack {
				  name: "RC Pack"
				  General {
				    main {
				      required: true
				      default-selected: true
				      from-server: [mods/*.jar, kubejs/**]
				    }
				    extras {
				      selection: recommended
				      auto-exclude-server-side-mods: PLATFORMS
				      from-server: [shaderpacks/**]
				    }
				  }
				}
				""", StandardCharsets.UTF_8);

		ServerConfigJsons.ServerConfigFieldsV3 config = TomlConfigs.read(toml, ServerConfigJsons.ServerConfigFieldsV3.class).orElseThrow();
		assertEquals("RC Pack", config.modpack.name);
		assertEquals(25570, config.bindPort);
		assertEquals("play.example.org", config.advertisedEndpointHost);
		assertEquals(ModpackConnectionMode.HTTP, config.connectionMode);
		assertFalse(config.validateSecrets);
		assertEquals(Set.of("fabric", "neoforge"), config.acceptedLoaders);
		assertEquals(GroupSelectionMode.REQUIRED, config.modpack.categories.get("General").get("main").selection);
		assertEquals(ServerSideModExclusion.DISABLED, config.modpack.categories.get("General").get("main").autoExcludeServerSideMods);
		assertEquals(Set.of("mods/*.jar", "kubejs/**"), config.modpack.categories.get("General").get("main").fromServer);
		assertEquals(GroupSelectionMode.RECOMMENDED, config.modpack.categories.get("General").get("extras").selection);
		assertEquals(ServerSideModExclusion.PLATFORMS, config.modpack.categories.get("General").get("extras").autoExcludeServerSideMods);
		assertTrue(Files.isRegularFile(toml));
		assertTrue(Files.isRegularFile(dir.resolve("server.conf.backup")));
		assertFalse(Files.exists(conf));

		// Re-reading the generated .toml yields the exact same values
		ServerConfigJsons.ServerConfigFieldsV3 reread = TomlConfigs.read(toml, ServerConfigJsons.ServerConfigFieldsV3.class).orElseThrow();
		assertEquals("RC Pack", reread.modpack.name);
		assertEquals(25570, reread.bindPort);
		assertEquals(ModpackConnectionMode.HTTP, reread.connectionMode);
		assertEquals(ServerSideModExclusion.DISABLED, reread.modpack.categories.get("General").get("main").autoExcludeServerSideMods);
		assertEquals(ServerSideModExclusion.PLATFORMS, reread.modpack.categories.get("General").get("extras").autoExcludeServerSideMods);
	}

	@Test
	void migratesV5RcClientConfToTomlAndBacksUpConf() throws Exception {
		Path toml = dir.resolve("client.toml");
		Path conf = dir.resolve("client.conf");
		Files.writeString(conf, """
				# v5-rc client config
				update-selected-modpack-on-launch: false
				self-updater: true
				sync-versions: false
				play-music: false
				pinned-mod-ids: [sodium, iris]
				""", StandardCharsets.UTF_8);

		ClientConfigJsons.ClientConfigFieldsV3 config = TomlConfigs.read(toml, ClientConfigJsons.ClientConfigFieldsV3.class).orElseThrow();
		assertFalse(config.updateSelectedModpackOnLaunch);
		assertTrue(config.selfUpdater);
		assertFalse(config.syncVersions);
		assertFalse(config.playMusic);
		assertEquals(List.of("sodium", "iris"), config.pinnedModIds);
		assertTrue(Files.isRegularFile(toml));
		assertTrue(Files.isRegularFile(dir.resolve("client.conf.backup")));
		assertFalse(Files.exists(conf));
	}

	@Test
	void requireMagicPacketsFalseWithDedicatedBindPortIsHttp() throws Exception {
		Path toml = dir.resolve("server.toml");
		Files.writeString(dir.resolve("automodpack-server.json"), """
				{
				  "modpackName": "Http Pack",
				  "syncedFiles": ["/mods/*.jar"],
				  "requireMagicPackets": false,
				  "bindPort": 25590
				}
				""", StandardCharsets.UTF_8);
		ServerConfigJsons.ServerConfigFieldsV3 config = TomlConfigs.readOrCreate(toml, ServerConfigJsons.ServerConfigFieldsV3.class, ServerConfigJsons.ServerConfigFieldsV3::new);
		assertEquals(ModpackConnectionMode.HTTP, config.connectionMode);
		assertEquals(25590, config.bindPort);
	}

	@Test
	void v4ClientJsonCopiesSettingsAndDoesNotInventAFollowId() throws Exception {
		Path toml = dir.resolve("client.toml");
		Files.writeString(dir.resolve("automodpack-client.json"), """
				{
				  "DO_NOT_CHANGE_IT": 2,
				  "selectedModpack": "My Pack",
				  "installedModpacks": {"My Pack": {}},
				  "allowRemoteNonModpackDeletions": true,
				  "updateSelectedModpackOnLaunch": false,
				  "selfUpdater": true,
				  "syncAutoModpackVersion": false,
				  "syncLoaderVersion": false,
				  "playMusic": false
				}
				""", StandardCharsets.UTF_8);
		ClientConfigJsons.ClientConfigFieldsV3 config = TomlConfigs.read(toml, ClientConfigJsons.ClientConfigFieldsV3.class).orElseThrow();
		assertFalse(config.updateSelectedModpackOnLaunch);
		assertTrue(config.selfUpdater);
		assertFalse(config.syncAutoModpackVersion);
		assertFalse(config.syncVersions);
		assertFalse(config.playMusic);
		assertTrue(Files.isRegularFile(toml));
		assertTrue(Files.isRegularFile(dir.resolve("automodpack-client.json.backup")));
		assertFalse(Files.exists(dir.resolve("client").resolve("selected.json")));
	}

	@Test
	void legacyGroupBooleansMapOntoTheSelectionEnum() {
		assertEquals(GroupSelectionMode.REQUIRED, selectionOf(mappedGroup("\"required\": true, \"default-selected\": true")), "(true, true) is the required core group");
		assertEquals(GroupSelectionMode.REQUIRED, selectionOf(mappedGroup("\"required\": true, \"default-selected\": false")), "the one pair with no mode of its own collapses to required");
		assertTrue(GroupSelectionMode.collapsed(true, false));
		assertEquals(GroupSelectionMode.REQUIRED, selectionOf(mappedGroup("\"required\": true")), "required alone still wins over a missing flag");
		assertEquals(GroupSelectionMode.RECOMMENDED, selectionOf(mappedGroup("\"defaultSelected\": true")));
		assertEquals(GroupSelectionMode.OPTIONAL, selectionOf(mappedGroup("\"required\": false, \"default-selected\": false")));
		assertEquals(GroupSelectionMode.OPTIONAL, selectionOf(mappedGroup("\"displayName\": \"Extra\"")), "a group without the pair starts unchecked");
	}

	@Test
	void selectionValuesParseInAnyCaseAndWinOverTheLegacyPair() {
		assertEquals(GroupSelectionMode.RECOMMENDED, selectionOf(mappedGroup("\"selection\": \"recommended\"")));
		assertEquals(GroupSelectionMode.OPTIONAL, selectionOf(mappedGroup("\"selection\": \"OPTIONAL\", \"required\": true, \"default-selected\": true")), "selection wins over the legacy pair");
	}

	@Test
	void unknownSelectionValueFailsLoudly() {
		assertThrows(ConfigTools.ConfigParseException.class, () -> selectionOf(mappedGroup("\"selection\": \"recommnded\"")));
	}

	@Test
	void nonStringSelectionAndNonBooleanLegacyFlagsFailLoudly() {
		assertThrows(ConfigTools.ConfigParseException.class, () -> selectionOf(mappedGroup("\"selection\": null")));
		assertThrows(ConfigTools.ConfigParseException.class, () -> selectionOf(mappedGroup("\"selection\": {}")));
		assertThrows(ConfigTools.ConfigParseException.class, () -> selectionOf(mappedGroup("\"required\": \"yes\"")), "\"yes\" must not silently read as false and drop a required group");
	}

	@Test
	void packLevelServerSideExclusionFalseDisablesGroupsThatOmitTheKey() {
		ServerConfigJsons.ServerConfigFieldsV3 config = HumanConfigMigration.mapServer(JsonParser.parseString("""
				{"autoExcludeServerSideMods": false, "modpack": {"General": {"main": {}, "kept": {"auto-exclude-server-side-mods": "PLATFORMS"}}}}
				""").getAsJsonObject());
		assertEquals(ServerSideModExclusion.DISABLED, config.modpack.categories.get("General").get("main").autoExcludeServerSideMods);
		assertEquals(ServerSideModExclusion.PLATFORMS, config.modpack.categories.get("General").get("kept").autoExcludeServerSideMods);
	}

	@Test
	void packLevelServerSideExclusionTrueLeavesGroupsAtMetadata() {
		assertEquals(ServerSideModExclusion.METADATA,
				HumanConfigMigration.mapServer(JsonParser.parseString("{\"auto-exclude-server-side-mods\": true, \"modpack\": {\"General\": {\"main\": {}}}}").getAsJsonObject()).modpack.categories.get("General")
						.get("main").autoExcludeServerSideMods);
	}

	private static ServerConfigJsons.ServerConfigFieldsV3 mappedGroup(String members) {
		return HumanConfigMigration.mapServer(JsonParser.parseString("{\"modpack\": {\"General\": {\"main\": {" + members + "}}}}").getAsJsonObject());
	}

	private static GroupSelectionMode selectionOf(ServerConfigJsons.ServerConfigFieldsV3 config) {
		return config.modpack.categories.get("General").get("main").selection;
	}
}
