package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;

import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

class HumanConfigMigrationTest {

	@TempDir
	Path dir;

	@Test
	void migratesV4ServerJsonOnReadAndStopsUsingTheBackup() throws Exception {
		Path conf = dir.resolve("server.conf");
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

		ServerConfigJsons.ServerConfigFieldsV3 config = ReconfConfigs.read(conf, ServerConfigJsons.ServerConfigFieldsV3.class).orElseThrow();
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
		assertTrue(Files.isRegularFile(conf));
		assertTrue(Files.isRegularFile(dir.resolve("automodpack-server.json.backup")));
		assertFalse(Files.exists(json));

		String text = Files.readString(conf, StandardCharsets.UTF_8);
		assertTrue(text.contains("from-server:"), text);
		assertTrue(text.contains("!kubejs/server_scripts/**"), text);

		Files.writeString(json, "{\"modpackName\":\"ignored\"}", StandardCharsets.UTF_8);
		ServerConfigJsons.ServerConfigFieldsV3 second = ReconfConfigs.read(conf, ServerConfigJsons.ServerConfigFieldsV3.class).orElseThrow();
		assertEquals("My Pack", second.modpack.name);
	}

	@Test
	void requireMagicPacketsFalseWithDedicatedBindPortIsHttp() throws Exception {
		Path conf = dir.resolve("server.conf");
		Files.writeString(dir.resolve("automodpack-server.json"), """
				{
				  "modpackName": "Http Pack",
				  "syncedFiles": ["/mods/*.jar"],
				  "requireMagicPackets": false,
				  "bindPort": 25590
				}
				""", StandardCharsets.UTF_8);
		ServerConfigJsons.ServerConfigFieldsV3 config = ReconfConfigs.readOrCreate(conf, ServerConfigJsons.ServerConfigFieldsV3.class, ServerConfigJsons.ServerConfigFieldsV3::new);
		assertEquals(ModpackConnectionMode.HTTP, config.connectionMode);
		assertEquals(25590, config.bindPort);
	}

	@Test
	void v4ClientJsonCopiesSettingsAndDoesNotInventAFollowId() throws Exception {
		Path conf = dir.resolve("client.conf");
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
		ClientConfigJsons.ClientConfigFieldsV3 config = ReconfConfigs.read(conf, ClientConfigJsons.ClientConfigFieldsV3.class).orElseThrow();
		assertFalse(config.updateSelectedModpackOnLaunch);
		assertTrue(config.selfUpdater);
		assertFalse(config.syncAutoModpackVersion);
		assertFalse(config.syncVersions);
		assertFalse(config.playMusic);
		assertTrue(Files.isRegularFile(conf));
		assertTrue(Files.isRegularFile(dir.resolve("automodpack-client.json.backup")));
		assertFalse(Files.exists(dir.resolve("client").resolve("selected.json")));
	}

	@Test
	void legacyGroupBooleansMapOntoTheSelectionEnum() {
		assertEquals(GroupSelectionMode.REQUIRED, selectionOf(mappedGroup("\"required\": true, \"default-selected\": true")), "(true, true) is the required core group");
		assertEquals(GroupSelectionMode.REQUIRED, selectionOf(mappedGroup("\"required\": true, \"default-selected\": false")), "a required group stays required regardless of its other flag");
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

	private static ServerConfigJsons.ServerConfigFieldsV3 mappedGroup(String members) {
		return HumanConfigMigration.mapServer(JsonParser.parseString("{\"modpack\": {\"General\": {\"main\": {" + members + "}}}}").getAsJsonObject());
	}

	private static GroupSelectionMode selectionOf(ServerConfigJsons.ServerConfigFieldsV3 config) {
		return config.modpack.categories.get("General").get("main").selection;
	}
}
