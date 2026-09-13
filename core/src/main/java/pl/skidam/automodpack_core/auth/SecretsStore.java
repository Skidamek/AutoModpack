package pl.skidam.automodpack_core.auth;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import pl.skidam.automodpack_core.config.AuthJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.storage.StoragePaths;

public class SecretsStore {
	static class SecretsCache {
		private final ConcurrentMap<String, IssuedSecret> cache;
		/** Reverse index behind {@link #bySecret}: secret string to the cache key a linear scan would answer with. */
		private final ConcurrentMap<String, String> keysBySecret;
		private AuthJsons.SecretsFields db;
		private final Path configFile;

		public SecretsCache(Path configFile) {
			this.configFile = configFile;
			this.cache = new ConcurrentHashMap<>();
			this.keysBySecret = new ConcurrentHashMap<>();
		}

		public synchronized void load() {
			if (db != null) return;
			db = ConfigTools.readOrCreate(configFile, AuthJsons.SecretsFields.class, AuthJsons.SecretsFields::new);
			if (db != null && db.secrets != null && !db.secrets.isEmpty()) cache.putAll(db.secrets);
			reindex();
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
			reindex();
		}

		/**
		 * The issued secret for {@code secret}, or null. Answers exactly what a linear scan of the cache would:
		 * entries with a null secret never match, equality stays case-sensitive string equality, and duplicate
		 * secrets resolve to the first match in cache iteration order — nondeterministic under the old scan, so
		 * the index fixing one arbitrary owner is at least as deterministic.
		 */
		public Map.Entry<String, IssuedSecret> bySecret(String secret) {
			if (secret == null) return null;
			String key = keysBySecret.get(secret);
			IssuedSecret issued = key == null ? null : cache.get(key);
			return issued == null ? null : Map.entry(key, issued);
		}

		/** Rebuilt from the whole cache on every mutation, so the index can only answer what a scan of the cache answers. */
		private void reindex() {
			keysBySecret.clear();
			for (var entry : cache.entrySet()) {
				IssuedSecret issued = entry.getValue();
				if (issued == null || issued.secret() == null) continue;
				keysBySecret.putIfAbsent(issued.secret(), entry.getKey());
			}
		}
	}

	private static final SecretsCache hostSecrets = new SecretsCache(StoragePaths.SERVER_SECRETS_FILE);

	/** The issued secret presented by a client, or null when no key was ever issued it. */
	public static Map.Entry<String, IssuedSecret> getHostSecret(String secret) {
		hostSecrets.load();
		return hostSecrets.bySecret(secret);
	}

	public static void saveHostSecret(String uuid, Secrets.Secret secret, String playerName) {
		hostSecrets.save(uuid, secret, playerName);
	}
}
