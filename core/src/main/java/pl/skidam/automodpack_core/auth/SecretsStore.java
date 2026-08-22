package pl.skidam.automodpack_core.auth;

import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.security.SharedSecurityPaths;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SecretsStore {
    private static class SecretsCache {
        private final ConcurrentMap<String, Secrets.Secret> cache;
        private Jsons.SecretsFields db;
        private final Path configFile;

        public SecretsCache(Path configFile) {
            this.configFile = configFile;
            this.cache = new ConcurrentHashMap<>();
        }

        public synchronized void load() {
            if (db != null)
                return;
            db = ConfigTools.load(configFile, Jsons.SecretsFields.class);
            if (db != null && db.secrets != null && !db.secrets.isEmpty()) {
                cache.putAll(db.secrets);
            }
        }

        public synchronized void save() {
            ConfigTools.save(configFile, db);
        }

        public Secrets.Secret get(String key) {
            load();
            return cache.get(key);
        }

        public void save(String key, Secrets.Secret secret) throws IllegalArgumentException {
            if (key == null || key.isBlank() || secret == null || secret.secret().isBlank())
                throw new IllegalArgumentException("Key or secret cannot be null or blank");
            load();
            cache.put(key, secret);
            if (db == null) {
                db = new Jsons.SecretsFields();
            }
            db.secrets.put(key, secret);
            save();
        }
    }

    private static final SecretsCache clientSecrets = new SecretsCache(GlobalVariables.clientSecretsFile);
    private static volatile SecretsCache localHostSecrets;
    private static volatile Path localHostSecretsPath;

    private static SecretsCache localHostSecrets() {
        Path path = GlobalVariables.serverSecretsFile;
        SecretsCache current = localHostSecrets;
        if (current == null || !path.equals(localHostSecretsPath)) {
            synchronized (SecretsStore.class) {
                current = localHostSecrets;
                if (current == null || !path.equals(localHostSecretsPath)) {
                    current = new SecretsCache(path);
                    localHostSecrets = current;
                    localHostSecretsPath = path;
                }
            }
        }
        return current;
    }

    public static SharedSecretsStore.HostSecretRecord findHostSecret(String secret) throws Exception {
        SharedSecurityPaths activeSharedSecurityPaths = GlobalVariables.sharedSecurityPaths;
        if (activeSharedSecurityPaths != null && activeSharedSecurityPaths.enabled()) {
            return new SharedSecretsStore(activeSharedSecurityPaths).find(secret);
        }

        SecretsCache hostSecrets = localHostSecrets();
        hostSecrets.load();
        for (var entry : hostSecrets.cache.entrySet()) {
            var thisSecret = entry.getValue().secret();
            if (Objects.equals(thisSecret, secret)) {
                Secrets.Secret stored = entry.getValue();
                long lifetimeSeconds = Math.max(0, GlobalVariables.serverConfig.secretLifetime) * 3600L;
                long expiresAt = stored.timestamp() + lifetimeSeconds;
                return new SharedSecretsStore.HostSecretRecord(
                        secret,
                        entry.getKey(),
                        "LOCAL",
                        stored.timestamp(),
                        expiresAt,
                        0
                );
            }
        }
        return null;
    }

    public static Map.Entry<String, Secrets.Secret> getHostSecret(String secret) {
        try {
            SharedSecretsStore.HostSecretRecord record = findHostSecret(secret);
            return record == null ? null : record.asLegacyEntry();
        } catch (Exception exception) {
            GlobalVariables.LOGGER.warn("Shared security store could not be read; rejecting secret: {}", exception.getMessage());
            return null;
        }
    }

    public static void saveHostSecret(String uuid, Secrets.Secret secret) {
        try {
            SharedSecurityPaths activeSharedSecurityPaths = GlobalVariables.sharedSecurityPaths;
            if (activeSharedSecurityPaths != null && activeSharedSecurityPaths.enabled()) {
                new SharedSecretsStore(activeSharedSecurityPaths)
                        .save(uuid, secret, GlobalVariables.serverConfig.secretLifetime);
            } else {
                localHostSecrets().save(uuid, secret);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not persist AutoModpack host secret", exception);
        }
    }

    public static Secrets.Secret getClientSecret(String modpack) {
        return clientSecrets.get(modpack);
    }

    public static void saveClientSecret(String modpack, Secrets.Secret secret) throws IllegalArgumentException {
        clientSecrets.save(modpack, secret);
    }
}
