package pl.skidam.automodpack_core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ServerConfigJsons;

class SecretsStoreTest {
	@TempDir
	Path temporaryDirectory;

	private ServerConfigJsons.ServerConfigFieldsV3 previousConfig;

	@BeforeEach
	void setUp() {
		previousConfig = Constants.serverConfig;
		Constants.serverConfig = new ServerConfigJsons.ServerConfigFieldsV3();
	}

	@AfterEach
	void tearDown() {
		Constants.serverConfig = previousConfig;
	}

	@Test
	void persistedFileNeverHoldsTheRawSecretAndIsKeyedBySha256Hex() throws Exception {
		SecretsStore.SecretsCache store = new SecretsStore.SecretsCache(secretsFile());
		String secret = Secrets.generateSecret().secret();
		store.save(playerId(), new Secrets.Secret(secret, now()), "alice");

		String persisted = Files.readString(secretsFile());
		assertFalse(persisted.contains(secret));
		JsonObject secrets = persistedSecrets(persisted);
		assertEquals(1, secrets.size());
		for (String key : secrets.keySet()) assertTrue(key.matches("[0-9a-f]{64}"), key);
	}

	@Test
	void reissuingForTheSamePlayerInvalidatesTheRotatedOutSecret() {
		Path file = secretsFile();
		SecretsStore.SecretsCache store = new SecretsStore.SecretsCache(file);
		String player = playerId();
		store.save(player, new Secrets.Secret("stale-secret", now()), "alice");
		store.save(player, new Secrets.Secret("fresh-secret", now()), "alice");

		assertNull(store.bySecret("stale-secret"));
		IssuedSecret issued = store.bySecret("fresh-secret");
		assertNotNull(issued);
		assertEquals(player, issued.playerId());
		assertEquals("alice", issued.name());

		SecretsStore.SecretsCache reloaded = new SecretsStore.SecretsCache(file);
		assertNull(reloaded.bySecret("stale-secret"));
		assertNotNull(reloaded.bySecret("fresh-secret"));
	}

	@Test
	void sharedNodesSeeEachOthersSecretsAfterACacheMiss() {
		SecretsStore.SecretsCache nodeA = new SecretsStore.SecretsCache(secretsFile());
		SecretsStore.SecretsCache nodeB = new SecretsStore.SecretsCache(secretsFile());

		nodeA.save(playerId(), new Secrets.Secret("alice-secret", now()), "alice");
		IssuedSecret onNodeB = nodeB.bySecret("alice-secret");
		assertNotNull(onNodeB);
		assertEquals("alice", onNodeB.name());

		nodeB.save(playerId(), new Secrets.Secret("bob-secret", now()), "bob");
		IssuedSecret onNodeA = nodeA.bySecret("bob-secret");
		assertNotNull(onNodeA);
		assertEquals("bob", onNodeA.name());

		// B's write merged with the doc instead of clobbering it, so alice keeps working everywhere.
		assertNotNull(nodeA.bySecret("alice-secret"));
		assertNotNull(nodeB.bySecret("bob-secret"));
	}

	@Test
	void savingPrunesAlreadyExpiredEntries() throws Exception {
		Constants.serverConfig.secretLifetime = 336;
		SecretsStore.SecretsCache store = new SecretsStore.SecretsCache(secretsFile());
		long now = now();
		store.save(playerId(), new Secrets.Secret("stale-secret", now - 2 * 3600), "ghost");

		assertEquals(1, persistedSecrets(Files.readString(secretsFile())).size());

		Constants.serverConfig.secretLifetime = 1; // the operator shortens the lifetime; the ghost entry is now expired
		store.save(playerId(), new Secrets.Secret("fresh-secret", now), "alice");

		assertNull(store.bySecret("stale-secret"));
		assertNotNull(store.bySecret("fresh-secret"));
		assertEquals(1, persistedSecrets(Files.readString(secretsFile())).size());
	}

	private Path secretsFile() {
		return temporaryDirectory.resolve("secrets.json");
	}

	private static JsonObject persistedSecrets(String persisted) {
		return JsonParser.parseString(persisted).getAsJsonObject().getAsJsonObject("secrets");
	}

	private static String playerId() {
		return UUID.randomUUID().toString();
	}

	private static long now() {
		return System.currentTimeMillis() / 1000;
	}
}
