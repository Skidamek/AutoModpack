package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.utils.AddressHelpers;

class ConfigToolsTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void readDoesNotCreateOrRewriteConfiguration() throws Exception {
		Path missing = temporaryDirectory.resolve("missing.json");
		assertTrue(ConfigTools.read(missing, Jsons.ClientConfigFieldsV3.class).isEmpty());
		assertFalse(Files.exists(missing));

		Path existing = temporaryDirectory.resolve("client.json");
		String json = "{\n  \"selectedModpackId\": \"pack\"\n}\n";
		Files.writeString(existing, json, StandardCharsets.UTF_8);

		assertEquals("pack", ConfigTools.read(existing, Jsons.ClientConfigFieldsV3.class).orElseThrow().selectedModpackId);
		assertEquals(json, Files.readString(existing, StandardCharsets.UTF_8));
	}

	@Test
	void invalidJsonIsNotTreatedAsMissingConfiguration() throws Exception {
		Path config = temporaryDirectory.resolve("invalid.json");
		Files.writeString(config, "{ invalid", StandardCharsets.UTF_8);

		assertThrows(ConfigTools.ConfigException.class, () -> ConfigTools.read(config, Jsons.ClientConfigFieldsV3.class));
		assertEquals("{ invalid", Files.readString(config, StandardCharsets.UTF_8));
	}

	@Test
	void readOrCreateOnlyWritesDefaultsWhenAbsent() throws Exception {
		Path config = temporaryDirectory.resolve("client.json");

		Jsons.ClientConfigFieldsV3 created = ConfigTools.readOrCreate(config, Jsons.ClientConfigFieldsV3.class, Jsons.ClientConfigFieldsV3::new);
		assertEquals(3, created.DO_NOT_CHANGE_IT);
		assertTrue(Files.isRegularFile(config));

		String existing = "{\"selectedModpackId\":\"preserve\"}";
		Files.writeString(config, existing, StandardCharsets.UTF_8);
		assertEquals("preserve", ConfigTools.readOrCreate(config, Jsons.ClientConfigFieldsV3.class, Jsons.ClientConfigFieldsV3::new).selectedModpackId);
		assertEquals(existing, Files.readString(config, StandardCharsets.UTF_8));
	}

	@Test
	void writeAtomicReplacesExistingConfiguration() throws Exception {
		Path config = temporaryDirectory.resolve("client.json");
		Files.writeString(config, "not-json", StandardCharsets.UTF_8);

		Jsons.ClientConfigFieldsV3 value = new Jsons.ClientConfigFieldsV3();
		value.selectedModpackId = "replacement";
		ConfigTools.writeAtomic(config, value);

		assertEquals("replacement", ConfigTools.read(config, Jsons.ClientConfigFieldsV3.class).orElseThrow().selectedModpackId);
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
				      "connectionMode": "MAGIC_PACKET"
				    }
				  }
				}
				""";
		Jsons.ClientConfigFieldsV3 config = ConfigTools.parse(configJson, Jsons.ClientConfigFieldsV3.class);

		assertEquals("pack", config.selectedModpackId);

		Path path = temporaryDirectory.resolve("client.json");
		ConfigTools.writeAtomic(path, config);
		String serialized = Files.readString(path, StandardCharsets.UTF_8);
		assertFalse(serialized.contains("\"modpackConnections\""));
		assertFalse(serialized.contains("installedModpacks"));
	}

	@Test
	void readsLegacyAdvertisedEndpointAliasesAndWritesOnlyNewNames() {
		Jsons.ServerConfigFieldsV2 config = ConfigTools.parse("{\"addressToSend\":\"downloads.example.com\",\"portToSend\":24444}",
				Jsons.ServerConfigFieldsV2.class);

		assertEquals("downloads.example.com", config.advertisedEndpointHost);
		assertEquals(24444, config.advertisedEndpointPort);
		String serialized = ConfigTools.GSON.toJson(config);
		assertTrue(serialized.contains("\"advertisedEndpointHost\""));
		assertTrue(serialized.contains("\"advertisedEndpointPort\""));
		assertFalse(serialized.contains("addressToSend"));
		assertFalse(serialized.contains("portToSend"));
	}

	@Test
	void connectionInfoCompletenessRequiresOriginAndEndpoint() {
		Jsons.ConnectionInfo connectionInfo = new Jsons.ConnectionInfo();
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
		assertThrows(ConfigTools.ConfigException.class, () -> ConfigTools.parse(invalid, Jsons.ConnectionInfo.class));
	}
}
