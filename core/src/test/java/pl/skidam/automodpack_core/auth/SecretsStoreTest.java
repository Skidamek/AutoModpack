package pl.skidam.automodpack_core.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecretsStoreTest {

    @TempDir
    Path tempDir;

    private Path secretsFile;

    @BeforeEach
    void setUp() {
        secretsFile = tempDir.resolve("automodpack-secrets.json");
        Constants.serverConfig = new Jsons.ServerConfigFieldsV2();
    }

    @AfterEach
    void tearDown() {
        Constants.serverConfig = null;
    }

    private static Secrets.Secret secretWithTimestamp(long timestamp) {
        Secrets.Secret secret = Secrets.generateSecret();
        return new Secrets.Secret(secret.secret(), timestamp);
    }

    @Test
    void addKeepsMultipleSecretsPerKey() {
        var cache = new SecretsStore.SecretsCache(secretsFile);

        Secrets.Secret first = Secrets.generateSecret();
        Secrets.Secret second = Secrets.generateSecret();
        cache.add("uuid", first);
        cache.add("uuid", second);

        List<Secrets.Secret> secrets = cache.get("uuid");
        assertEquals(2, secrets.size());
        assertEquals(first.secret(), secrets.get(0).secret());
        assertEquals(second.secret(), secrets.get(1).secret());
    }

    @Test
    void addDeduplicatesSameSecret() {
        var cache = new SecretsStore.SecretsCache(secretsFile);

        Secrets.Secret secret = Secrets.generateSecret();
        cache.add("uuid", secret);
        cache.add("uuid", secret);

        assertEquals(1, cache.get("uuid").size());
    }

    @Test
    void addCapsSecretsPerKeyDroppingOldest() {
        var cache = new SecretsStore.SecretsCache(secretsFile);

        long now = System.currentTimeMillis() / 1000;
        Secrets.Secret oldest = secretWithTimestamp(now - 100);
        cache.add("uuid", oldest);
        for (int i = 0; i < 15; i++) {
            cache.add("uuid", secretWithTimestamp(now + i));
        }

        List<Secrets.Secret> secrets = cache.get("uuid");
        assertTrue(secrets.size() <= 10, "cap exceeded: " + secrets.size());
        assertTrue(secrets.stream().noneMatch(s -> s.secret().equals(oldest.secret())), "oldest should be pruned");
    }

    @Test
    void addPrunesExpiredSecrets() {
        var cache = new SecretsStore.SecretsCache(secretsFile);

        long lifetimeSeconds = Constants.serverConfig.secretLifetime * 3600;
        long expired = System.currentTimeMillis() / 1000 - lifetimeSeconds - 10;
        cache.add("uuid", secretWithTimestamp(expired));
        Secrets.Secret fresh = Secrets.generateSecret();
        cache.add("uuid", fresh);

        List<Secrets.Secret> secrets = cache.get("uuid");
        assertEquals(1, secrets.size());
        assertEquals(fresh.secret(), secrets.get(0).secret());
    }

    @Test
    void replaceKeepsOnlyLatest() {
        var cache = new SecretsStore.SecretsCache(secretsFile);

        cache.replace("modpack", Secrets.generateSecret());
        Secrets.Secret latest = Secrets.generateSecret();
        cache.replace("modpack", latest);

        List<Secrets.Secret> secrets = cache.get("modpack");
        assertEquals(1, secrets.size());
        assertEquals(latest.secret(), secrets.get(0).secret());
    }

    @Test
    void persistsAndReloadsAcrossInstances() {
        var cache = new SecretsStore.SecretsCache(secretsFile);
        Secrets.Secret secret = Secrets.generateSecret();
        cache.add("uuid", secret);

        var reloaded = new SecretsStore.SecretsCache(secretsFile);
        List<Secrets.Secret> secrets = reloaded.get("uuid");
        assertEquals(1, secrets.size());
        assertEquals(secret.secret(), secrets.get(0).secret());
        assertEquals(secret.timestamp(), secrets.get(0).timestamp());
    }

    @Test
    void migratesLegacySingleSecretFormat() throws IOException {
        // legacy: map value is a single secret object, not an array
        Secrets.Secret legacySecret = Secrets.generateSecret();
        String legacyJson = """
                {
                  "secrets": {
                    "some-uuid": {
                      "secret": "%s",
                      "timestamp": %d
                    }
                  }
                }
                """.formatted(legacySecret.secret(), legacySecret.timestamp());
        Files.writeString(secretsFile, legacyJson);

        var cache = new SecretsStore.SecretsCache(secretsFile);
        List<Secrets.Secret> secrets = cache.get("some-uuid");
        assertEquals(1, secrets.size());
        assertEquals(legacySecret.secret(), secrets.get(0).secret());
        assertEquals(legacySecret.timestamp(), secrets.get(0).timestamp());

        // file rewritten in the new format
        var migrated = ConfigTools.softLoad(secretsFile, Jsons.SecretsFields.class);
        assertNotNull(migrated);
        assertNotNull(migrated.secrets.get("some-uuid"));
        assertEquals(1, migrated.secrets.get("some-uuid").size());
    }

    @Test
    void handlesMissingFile() {
        var cache = new SecretsStore.SecretsCache(secretsFile);
        assertTrue(cache.get("nope").isEmpty());
    }
}
