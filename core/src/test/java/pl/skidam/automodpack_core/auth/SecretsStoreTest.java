package pl.skidam.automodpack_core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretsStoreTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void lookupResolvesEachIssuedSecretToItsOwnKey() {
		SecretsStore.SecretsCache store = new SecretsStore.SecretsCache(temporaryDirectory.resolve("secrets.json"));
		store.save("uuid-first", new Secrets.Secret("first-secret", 1L), "alice");
		store.save("uuid-second", new Secrets.Secret("second-secret", 2L), "bob");

		Map.Entry<String, IssuedSecret> first = store.bySecret("first-secret");
		assertNotNull(first);
		assertEquals("uuid-first", first.getKey());
		assertEquals("alice", first.getValue().name());
		assertEquals("first-secret", first.getValue().secret());
		assertEquals("uuid-second", store.bySecret("second-secret").getKey());

		assertNull(store.bySecret("unknown-secret"));
		assertNull(store.bySecret(null));
	}

	@Test
	void lookupSurvivesAFreshStoreOverTheSameFile() {
		Path secretsFile = temporaryDirectory.resolve("secrets.json");
		SecretsStore.SecretsCache store = new SecretsStore.SecretsCache(secretsFile);
		store.save("uuid-first", new Secrets.Secret("first-secret", 1L), "alice");

		SecretsStore.SecretsCache reloaded = new SecretsStore.SecretsCache(secretsFile);
		reloaded.load();
		Map.Entry<String, IssuedSecret> lookedUp = reloaded.bySecret("first-secret");
		assertNotNull(lookedUp);
		assertEquals("uuid-first", lookedUp.getKey());
		assertEquals("alice", lookedUp.getValue().name());
	}

	@Test
	void duplicatedSecretResolvesStablyLikeTheOldFirstMatchScan() {
		SecretsStore.SecretsCache store = new SecretsStore.SecretsCache(temporaryDirectory.resolve("secrets.json"));
		store.save("uuid-original", new Secrets.Secret("shared-secret", 1L), "alice");
		store.save("uuid-duplicate", new Secrets.Secret("shared-secret", 2L), "bob");

		Map.Entry<String, IssuedSecret> match = store.bySecret("shared-secret");
		assertNotNull(match);
		// The old scan answered with whichever duplicate came first in ConcurrentHashMap order, so any fixed
		// owner of the secret preserves the old answers; it must just be one of them, every time.
		assertTrue(Set.of("uuid-original", "uuid-duplicate").contains(match.getKey()));
		assertEquals("shared-secret", match.getValue().secret());
		assertEquals(match.getKey(), store.bySecret("shared-secret").getKey());
		assertSame(match.getValue(), store.bySecret("shared-secret").getValue());
	}

	@Test
	void reissuingAKeyMovesItsOldSecretOutOfTheIndex() {
		SecretsStore.SecretsCache store = new SecretsStore.SecretsCache(temporaryDirectory.resolve("secrets.json"));
		store.save("uuid-a", new Secrets.Secret("stale-secret", 1L), "alice");
		store.save("uuid-b", new Secrets.Secret("fresh-secret", 2L), "bob");
		store.save("uuid-a", new Secrets.Secret("fresh-secret", 3L), "alice-again");

		assertNull(store.bySecret("stale-secret"));
		Map.Entry<String, IssuedSecret> fresh = store.bySecret("fresh-secret");
		assertNotNull(fresh);
		assertEquals("fresh-secret", fresh.getValue().secret());
	}
}
