package pl.skidam.automodpack_core.auth;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import pl.skidam.automodpack_core.config.AuthJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.storage.StoragePaths;
import pl.skidam.automodpack_core.update.ClientStorage;

public class SecretsStore {
	private static class SecretsCache {
		private final ConcurrentMap<String, Secrets.Secret> cache;
		private AuthJsons.SecretsFields db;
		private final Path configFile;

		public SecretsCache(Path configFile) {
			this.configFile = configFile;
			this.cache = new ConcurrentHashMap<>();
		}

		public synchronized void load() {
			if (db != null) return;
			db = ConfigTools.readOrCreate(configFile, AuthJsons.SecretsFields.class, AuthJsons.SecretsFields::new);
			if (db != null && db.secrets != null && !db.secrets.isEmpty()) cache.putAll(db.secrets);
		}

		public synchronized void save() {
			try {
				ConfigTools.writeAtomic(configFile, db);
			} catch (IOException e) {
				throw new ConfigTools.ConfigException("Failed to save secrets", e);
			}
		}

		public Secrets.Secret get(String key) {
			load();
			return cache.get(key);
		}

		public synchronized void save(String key, Secrets.Secret secret) throws IllegalArgumentException {
			if (key == null || key.isBlank() || secret == null || secret.secret().isBlank())
				throw new IllegalArgumentException("Key or secret cannot be null or blank");
			load();
			cache.put(key, secret);
			if (db == null) db = new AuthJsons.SecretsFields();
			if (db.secrets == null) db.secrets = new ConcurrentHashMap<>();
			db.secrets.put(key, secret);
			save();
		}

	}

	private static final SecretsCache hostSecrets = new SecretsCache(StoragePaths.SERVER_SECRETS_FILE);

	public static Map.Entry<String, Secrets.Secret> getHostSecret(String secret) {
		hostSecrets.load();
		for (var entry : hostSecrets.cache.entrySet()) {
			var thisSecret = entry.getValue().secret();
			if (Objects.equals(thisSecret, secret)) return entry;
		}

		return null;
	}

	public static void saveHostSecret(String uuid, Secrets.Secret secret) {
		hostSecrets.save(uuid, secret);
	}

	public static Secrets.Secret getClientSecret(ClientStorage storage, String modpackId, InetSocketAddress origin) {
		try {
			return ConnectionStore.getClientSecret(storage, modpackId, origin);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to load client secret", e);
		}
	}

	public static void saveClientSecret(ClientStorage storage, String modpackId, InetSocketAddress origin, Secrets.Secret secret) throws IllegalArgumentException {
		try {
			ConnectionStore.saveClientSecret(storage, modpackId, origin, secret);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save client secret", e);
		}
	}
}
