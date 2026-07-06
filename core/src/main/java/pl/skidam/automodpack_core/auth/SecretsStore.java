package pl.skidam.automodpack_core.auth;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SecretsStore {
    // Multiple secrets may be live per key (player uuid on the host, modpack on the client) so
    // that e.g. connecting from a second instance/launcher doesn't invalidate the first one.
    private static final int MAX_SECRETS_PER_KEY = 10;

    static class SecretsCache { // package-private for tests
        private final ConcurrentMap<String, List<Secrets.Secret>> cache;
        private Jsons.SecretsFields db;
        private final Path configFile;

        public SecretsCache(Path configFile) {
            this.configFile = configFile;
            this.cache = new ConcurrentHashMap<>();
        }

        public synchronized void load() {
            if (db != null)
                return;
            db = ConfigTools.softLoad(configFile, Jsons.SecretsFields.class);
            if (db == null && Files.isRegularFile(configFile)) {
                migrateLegacy();
            }
            if (db == null) {
                db = new Jsons.SecretsFields();
            }
            if (db.secrets == null) {
                db.secrets = new java.util.HashMap<>();
            }
            db.secrets.values().removeIf(Objects::isNull);
            cache.putAll(db.secrets);
        }

        // Pre-5.0 format stored a single secret per key.
        private void migrateLegacy() {
            var legacy = ConfigTools.softLoad(configFile, Jsons.SecretsFieldsV1.class);
            if (legacy == null || legacy.secrets == null)
                return;
            db = new Jsons.SecretsFields();
            for (var entry : legacy.secrets.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null)
                    continue;
                List<Secrets.Secret> list = new ArrayList<>(1);
                list.add(entry.getValue());
                db.secrets.put(entry.getKey(), list);
            }
            save();
            Constants.LOGGER.info("Migrated secrets file to the new multi-secret format: {}", configFile);
        }

        private synchronized void save() {
            ConfigTools.save(configFile, db);
        }

        public List<Secrets.Secret> get(String key) {
            load();
            List<Secrets.Secret> secrets = cache.get(key);
            return secrets == null ? List.of() : List.copyOf(secrets);
        }

        public synchronized void add(String key, Secrets.Secret secret) throws IllegalArgumentException {
            if (key == null || key.isBlank() || secret == null || secret.secret().isBlank())
                throw new IllegalArgumentException("Key or secret cannot be null or blank");
            load();
            List<Secrets.Secret> secrets = new ArrayList<>(get(key));
            secrets.removeIf(s -> s == null || Objects.equals(s.secret(), secret.secret()));
            secrets.add(secret);
            prune(secrets);
            cache.put(key, secrets);
            db.secrets.put(key, secrets);
            save();
        }

        public synchronized void replace(String key, Secrets.Secret secret) throws IllegalArgumentException {
            if (key == null || key.isBlank() || secret == null || secret.secret().isBlank())
                throw new IllegalArgumentException("Key or secret cannot be null or blank");
            load();
            List<Secrets.Secret> secrets = new ArrayList<>(1);
            secrets.add(secret);
            cache.put(key, secrets);
            db.secrets.put(key, secrets);
            save();
        }

        public synchronized void persist() {
            load();
            save();
        }

        // Keeps the list bounded and free of long-expired entries. Expiry pruning only applies
        // where the lifetime is known (the host side); newest secrets always survive the cap.
        private void prune(List<Secrets.Secret> secrets) {
            secrets.sort(Comparator.comparingLong(s -> s.timestamp() == null ? 0 : s.timestamp()));
            // called right after adding a fresh secret, so this can never empty the list
            if (Constants.serverConfig != null && Constants.serverConfig.secretLifetime > 0) {
                long cutoff = System.currentTimeMillis() / 1000 - Constants.serverConfig.secretLifetime * 3600;
                secrets.removeIf(s -> s.timestamp() != null && s.timestamp() < cutoff);
            }
            while (secrets.size() > MAX_SECRETS_PER_KEY) {
                secrets.remove(0); // oldest first
            }
        }
    }

    private static final SecretsCache hostSecrets = new SecretsCache(Constants.serverSecretsFile);
    private static final SecretsCache clientSecrets = new SecretsCache(Constants.clientSecretsFile);

    public static Map.Entry<String, Secrets.Secret> getHostSecret(String secret) {
        hostSecrets.load();
        for (var entry : hostSecrets.cache.entrySet()) {
            for (Secrets.Secret thisSecret : entry.getValue()) {
                if (thisSecret != null && Objects.equals(thisSecret.secret(), secret)) {
                    return Map.entry(entry.getKey(), thisSecret);
                }
            }
        }

        return null;
    }

    public static void saveHostSecret(String uuid, Secrets.Secret secret) {
        hostSecrets.add(uuid, secret);
    }

    // Persists in-memory changes (e.g. refreshed timestamps) of an already stored secret.
    public static void persistHostSecrets() {
        hostSecrets.persist();
    }

    public static Secrets.Secret getClientSecret(String modpack) {
        List<Secrets.Secret> secrets = clientSecrets.get(modpack);
        if (secrets.isEmpty())
            return null;
        // newest one - the client only ever needs the most recently issued secret
        return secrets.get(secrets.size() - 1);
    }

    public static void saveClientSecret(String modpack, Secrets.Secret secret) throws IllegalArgumentException {
        clientSecrets.replace(modpack, secret);
    }
}
