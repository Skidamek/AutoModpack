package pl.skidam.automodpack_core.auth;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import pl.skidam.automodpack_core.config.AuthJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.storage.StoragePaths;

public class SecretsStore {
	private static class SecretsCache {
		private final ConcurrentMap<String, IssuedSecret> cache;
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

		public synchronized void save(String key, Secrets.Secret secret, String playerName) throws IllegalArgumentException {
			if (key == null || key.isBlank() || secret == null || secret.secret().isBlank() || playerName == null || playerName.isBlank())
				throw new IllegalArgumentException("Key, secret and player name cannot be null or blank");
			load();
			IssuedSecret issued = new IssuedSecret(secret, playerName);
			cache.put(key, issued);
			if (db == null) db = new AuthJsons.SecretsFields();
			if (db.secrets == null) db.secrets = new ConcurrentHashMap<>();
			db.secrets.put(key, issued);
			save();
		}
	}

	private static final SecretsCache hostSecrets = new SecretsCache(StoragePaths.SERVER_SECRETS_FILE);

	public static Map.Entry<String, IssuedSecret> getHostSecret(String secret) {
		hostSecrets.load();
		for (var entry : hostSecrets.cache.entrySet()) {
			IssuedSecret issued = entry.getValue();
			if (issued == null || issued.secret() == null) continue;
			if (Objects.equals(issued.secret(), secret)) return entry;
		}

		return null;
	}

	public static void saveHostSecret(String uuid, Secrets.Secret secret, String playerName) {
		hostSecrets.save(uuid, secret, playerName);
	}
}
