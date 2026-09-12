package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.utils.AddressHelpers;

class ConfigToolsTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void readDoesNotCreateOrRewriteConfiguration() throws Exception {
		Path missing = temporaryDirectory.resolve("missing.json");
		assertTrue(ConfigTools.read(missing, ClientConfigJsons.ClientConfigFieldsV3.class).isEmpty());
		assertFalse(Files.exists(missing));

		Path existing = temporaryDirectory.resolve("client.json");
		String json = "{\n  \"selectedModpackId\": \"pack\"\n}\n";
		Files.writeString(existing, json, StandardCharsets.UTF_8);

		assertEquals("pack", ConfigTools.read(existing, ClientConfigJsons.ClientConfigFieldsV3.class).orElseThrow().selectedModpackId);
		assertEquals(json, Files.readString(existing, StandardCharsets.UTF_8));
	}

	@Test
	void invalidJsonIsNotTreatedAsMissingConfiguration() throws Exception {
		Path config = temporaryDirectory.resolve("invalid.json");
		Files.writeString(config, "{ invalid", StandardCharsets.UTF_8);

		assertThrows(ConfigTools.ConfigException.class, () -> ConfigTools.read(config, ClientConfigJsons.ClientConfigFieldsV3.class));
		assertEquals("{ invalid", Files.readString(config, StandardCharsets.UTF_8));
	}

	@Test
	void readOrCreateOnlyWritesDefaultsWhenAbsent() throws Exception {
		Path config = temporaryDirectory.resolve("client.json");

		ClientConfigJsons.ClientConfigFieldsV3 created = ConfigTools.readOrCreate(config, ClientConfigJsons.ClientConfigFieldsV3.class, ClientConfigJsons.ClientConfigFieldsV3::new);
		assertEquals(3, created.DO_NOT_CHANGE_IT);
		assertTrue(Files.isRegularFile(config));

		String existing = "{\"selectedModpackId\":\"preserve\"}";
		Files.writeString(config, existing, StandardCharsets.UTF_8);
		assertEquals("preserve", ConfigTools.readOrCreate(config, ClientConfigJsons.ClientConfigFieldsV3.class, ClientConfigJsons.ClientConfigFieldsV3::new).selectedModpackId);
		assertEquals(existing, Files.readString(config, StandardCharsets.UTF_8));
	}

	@Test
	void writeAtomicReplacesExistingConfiguration() throws Exception {
		Path config = temporaryDirectory.resolve("client.json");
		Files.writeString(config, "not-json", StandardCharsets.UTF_8);

		ClientConfigJsons.ClientConfigFieldsV3 value = new ClientConfigJsons.ClientConfigFieldsV3();
		value.selectedModpackId = "replacement";
		ConfigTools.writeAtomic(config, value);

		assertEquals("replacement", ConfigTools.read(config, ClientConfigJsons.ClientConfigFieldsV3.class).orElseThrow().selectedModpackId);
		try (var files = Files.list(temporaryDirectory)) {
			assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
		}
	}

	@Test
	void keepsConnectionStateOutOfTheUserConfig() throws Exception {
		String configJson = """
				{
				  "selectedModpackId": "pack",
				  "installedModpacks": {
				    "pack": {
				      "serverAddress": "Play.Example.com",
				      "hostAddress": "[2001:0DB8:0:0:0:0:0:1]:24444",
				      "connectionMode": "MAGIC"
				    }
				  }
				}
				""";
		ClientConfigJsons.ClientConfigFieldsV3 config = ConfigTools.parse(configJson, ClientConfigJsons.ClientConfigFieldsV3.class);

		assertEquals("pack", config.selectedModpackId);

		Path path = temporaryDirectory.resolve("client.json");
		ConfigTools.writeAtomic(path, config);
		String serialized = Files.readString(path, StandardCharsets.UTF_8);
		assertFalse(serialized.contains("\"modpackConnections\""));
		assertFalse(serialized.contains("installedModpacks"));
	}

	@Test
	void connectionInfoCompletenessRequiresOriginAndEndpoint() {
		ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo();
		assertFalse(connectionInfo.isComplete());

		connectionInfo.origin = AddressHelpers.parseOrigin("play.example.com");
		assertFalse(connectionInfo.isComplete());

		connectionInfo.endpoint = AddressHelpers.parseEndpoint("downloads.example.com:24444");
		assertFalse(connectionInfo.isComplete());

		connectionInfo.connectionMode = ModpackConnectionMode.DIRECT;
		assertTrue(connectionInfo.isComplete());
	}

	@Test
	void connectionSchemaRejectsEndpointWithoutPort() {
		String invalid = "{\"origin\":\"play.example.com\",\"endpoint\":\"downloads.example.com\"}";
		assertThrows(ConfigTools.ConfigException.class, () -> ConfigTools.parse(invalid, ConnectionJsons.ConnectionInfo.class));
	}

	@Test
	void unknownEnumNamesFailLoudlyInsteadOfYieldingNulls() {
		String json = "{\"reasons\":[\"GONE_REASON\",\"REPLACED_BY_UPSTREAM\"]}";

		ConfigTools.ConfigException failure = assertThrows(ConfigTools.ConfigException.class,
				() -> ConfigTools.parse(json, EnumHolder.class));

		assertTrue(String.valueOf(failure.getCause().getMessage()).contains("Unknown RestartReason value 'GONE_REASON'"), failure.getMessage());
	}

	@Test
	void validEnumNamesStillParseIntoLists() {
		EnumHolder holder = ConfigTools.parse("{\"reasons\":[\"SELECTED_MODPACK\",\"CHANGED_GROUP_SELECTION\"]}", EnumHolder.class);

		assertEquals(List.of(UpdatePlan.RestartReason.SELECTED_MODPACK, UpdatePlan.RestartReason.CHANGED_GROUP_SELECTION), holder.reasons);
	}

	@Test
	void unknownTopLevelAndNestedKeysAreCollected() {
		String json = """
				{
				  "syncedfile": "typo",
				  "groups": {
				    "main": {"syncedfile": "typo"},
				    "extra": {"displayName": "Extra", "bogus": true}
				  }
				}
				""";

		assertEquals(List.of("syncedfile", "groups.main.syncedfile", "groups.extra.bogus"), ConfigTools.unknownKeys(json, ServerConfigJsons.ServerConfigFieldsV3.class));
	}

	@Test
	void validSerializedConfigurationsHaveNoUnknownKeys() {
		assertTrue(ConfigTools.unknownKeys(ConfigTools.GSON.toJson(new ServerConfigJsons.ServerConfigFieldsV3()), ServerConfigJsons.ServerConfigFieldsV3.class).isEmpty());
		assertTrue(ConfigTools.unknownKeys(ConfigTools.GSON.toJson(new ClientConfigJsons.ClientConfigFieldsV3()), ClientConfigJsons.ClientConfigFieldsV3.class).isEmpty());
	}

	@Test
	void wrongTypedValueFailsLoudlyWithoutTouchingTheFile() throws Exception {
		Path config = temporaryDirectory.resolve("server-config.json");
		String json = "{\"bindPort\": \"x\"}";
		Files.writeString(config, json, StandardCharsets.UTF_8);

		assertThrows(ConfigTools.ConfigException.class, () -> ConfigTools.readOrCreate(config, ServerConfigJsons.ServerConfigFieldsV3.class, ServerConfigJsons.ServerConfigFieldsV3::new));
		assertEquals(json, Files.readString(config, StandardCharsets.UTF_8));
	}

	@Test
	void readStateTreatsUnusableContentAsAbsentAndSetsItAside() throws Exception {
		Path missing = temporaryDirectory.resolve("missing.json");
		assertTrue(ConfigTools.readState(missing, StateDocument.class, "Test state", StateDocument::validated).isEmpty());
		assertFalse(Files.exists(missing));

		Path unusable = temporaryDirectory.resolve("state.json");
		Files.writeString(unusable, "{\"value\": -1}", StandardCharsets.UTF_8);
		assertTrue(ConfigTools.readState(unusable, StateDocument.class, "Test state", StateDocument::validated).isEmpty());
		assertFalse(Files.exists(unusable));
		try (var leftovers = Files.list(temporaryDirectory)) {
			List<Path> aside = leftovers.filter(path -> path.getFileName().toString().startsWith("state.json.corrupt-")).toList();
			assertEquals(1, aside.size());
			assertEquals("{\"value\": -1}", Files.readString(aside.get(0)));
		}

		Files.writeString(unusable, "{\"value\": 3}", StandardCharsets.UTF_8);
		assertEquals(3, ConfigTools.readState(unusable, StateDocument.class, "Test state", StateDocument::validated).orElseThrow().value);
	}

	@Test
	void readStateStillFailsOnAFileThatIsNotARegularFile() throws Exception {
		Path directory = temporaryDirectory.resolve("state.json");
		Files.createDirectory(directory);

		assertThrows(IOException.class, () -> ConfigTools.readState(directory, StateDocument.class, "Test state", StateDocument::validated));
	}

	public static class StateDocument {
		public int value;

		public static StateDocument validated(StateDocument document) {
			if (document.value < 0) throw new IllegalArgumentException("Test state value is invalid");
			return document;
		}
	}

	public static class EnumHolder {
		public List<UpdatePlan.RestartReason> reasons;
	}
}
