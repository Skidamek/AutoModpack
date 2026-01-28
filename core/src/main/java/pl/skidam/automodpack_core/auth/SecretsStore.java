package pl.skidam.automodpack_core.auth;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

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

    private static final SecretsCache hostSecrets = new SecretsCache(Constants.serverSecretsFile);
    private static final SecretsCache clientSecrets = new SecretsCache(Constants.clientSecretsFile);

    public static Map.Entry<String, Secrets.Secret>  getHostSecret(String secret) {
        hostSecrets.load();
        for (var entry : hostSecrets.cache.entrySet()) {
            var thisSecret = entry.getValue().secret();
            if (Objects.equals(thisSecret, secret)) {
                return entry;
            }
        }

        return null;
    }

    public static void saveHostSecret(String uuid, Secrets.Secret secret) {
        hostSecrets.save(uuid, secret);
    }

    public static Secrets.Secret getClientSecret(String modpack) {
        return clientSecrets.get(modpack);
    }

    public static void saveClientSecret(String modpack, Secrets.Secret secret) throws IllegalArgumentException {
        clientSecrets.save(modpack, secret);
    }
}
