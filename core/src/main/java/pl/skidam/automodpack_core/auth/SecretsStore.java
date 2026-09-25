package pl.skidam.automodpack_core.auth;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.serverConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.config.AuthJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.storage.StoragePaths;
import pl.skidam.automodpack_core.utils.FileLocks;
import pl.skidam.automodpack_core.utils.TimedSet;

public class SecretsStore {
	static class SecretsCache {
		private static final Pattern KEY_SHAPE = Pattern.compile("[0-9a-f]{64}");
		private static final String DESCRIPTION = "Host secrets";

		// Swapped whole on every save and reload, so a validating reader sees the old map or the new one, never a half-built one.
		private volatile ConcurrentMap<String, IssuedSecret> cache = new ConcurrentHashMap<>();
		// Recently failed lookups, so a flood of bogus secrets cannot turn into a flood of locked full-file reads.
		private final TimedSet<String> knownAbsent = new TimedSet<>(3500);
		private final Path file;

		SecretsCache(Path file) {
			this.file = file;
		}

		/** The issued secret presented by a client, or null when no key was ever issued it. The folder may be shared by several server processes, so a cache miss reloads the document once before giving up. */
		public IssuedSecret bySecret(String secret) {
			if (secret == null) return null;
			String key = hash(secret);
			IssuedSecret issued = cache.get(key);
			if (issued != null) return issued;
			if (knownAbsent.contains(secret)) return null;
			try {
				FileLocks.withLock(lockFile(), () -> {
					cache = new ConcurrentHashMap<>(readSecrets());
					return null;
				});
			} catch (IOException | ConfigTools.ConfigException e) {
				LOGGER.error("Failed to read the shared host secrets, rejecting the presented secret", e);
				return null;
			}
			issued = cache.get(key);
			if (issued == null) knownAbsent.add(secret);
			return issued;
		}

		public synchronized void save(String playerId, Secrets.Secret secret, String playerName) throws IllegalArgumentException {
			if (playerId == null || playerId.isBlank() || secret == null || secret.secret().isBlank() || playerName == null || playerName.isBlank())
				throw new IllegalArgumentException("Player id, secret and player name cannot be null or blank");
			String key = hash(secret.secret());
			IssuedSecret issued = new IssuedSecret(playerId, secret.timestamp(), playerName);
			try {
				FileLocks.withLock(lockFile(), () -> {
					// Read fresh inside the lock: the folder may be shared, so an in-memory view is never authoritative.
					Map<String, IssuedSecret> fresh = readSecrets();
					replaceByPlayer(fresh, playerId, key, issued);
					pruneExpired(fresh);
					write(fresh);
					cache = new ConcurrentHashMap<>(fresh);
					return null;
				});
			} catch (IOException e) {
				throw new ConfigTools.ConfigException("Failed to save the host secrets", e);
			}
			knownAbsent.remove(secret.secret());
		}

		private Map<String, IssuedSecret> readSecrets() {
			try {
				return new ConcurrentHashMap<>(ConfigTools.readState(file, AuthJsons.SecretsFields.class, DESCRIPTION, SecretsCache::validated).orElseGet(Map::of));
			} catch (IOException e) {
				throw new ConfigTools.ConfigException("Failed to read " + DESCRIPTION, e);
			}
		}

		private void write(Map<String, IssuedSecret> secrets) throws IOException {
			AuthJsons.SecretsFields fields = new AuthJsons.SecretsFields();
			fields.secrets = new ConcurrentHashMap<>(secrets);
			ConfigTools.writeAtomic(file, fields);
		}

		private static Map<String, IssuedSecret> validated(AuthJsons.SecretsFields fields) {
			if (fields == null || fields.secrets == null) return Map.of();
			for (var entry : fields.secrets.entrySet()) {
				if (!KEY_SHAPE.matcher(entry.getKey()).matches()) throw new ConfigTools.ConfigParseException(DESCRIPTION + " key is not a lowercase SHA-256 hex digest: " + entry.getKey());
				IssuedSecret issued = entry.getValue();
				if (issued == null || issued.playerId() == null || issued.playerId().isBlank()) throw new ConfigTools.ConfigParseException(DESCRIPTION + " entry " + entry.getKey() + " has no player id");
				try {
					UUID.fromString(issued.playerId());
				} catch (IllegalArgumentException e) {
					throw new ConfigTools.ConfigParseException(DESCRIPTION + " entry " + entry.getKey() + " has a player id that is not a UUID: " + issued.playerId(), e);
				}
				if (issued.name() == null || issued.name().isBlank()) throw new ConfigTools.ConfigParseException(DESCRIPTION + " entry " + entry.getKey() + " has no player name");
				if (issued.timestamp() == null || issued.timestamp() <= 0) throw new ConfigTools.ConfigParseException(DESCRIPTION + " entry " + entry.getKey() + " has no issue timestamp");
			}
			return fields.secrets;
		}

		private static void replaceByPlayer(Map<String, IssuedSecret> secrets, String playerId, String key, IssuedSecret issued) {
			secrets.values().removeIf(existing -> existing != null && playerId.equals(existing.playerId()));
			secrets.put(key, issued);
		}

		private static void pruneExpired(Map<String, IssuedSecret> secrets) {
			long lifetimeSeconds = serverConfig.secretLifetime * 3600;
			long now = System.currentTimeMillis() / 1000;
			secrets.values().removeIf(issued -> issued == null || issued.timestamp() == null || issued.timestamp() + lifetimeSeconds <= now);
		}

		private Path lockFile() {
			return file.resolveSibling(file.getFileName() + ".lock");
		}
	}

	private static String hash(String secret) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private static final SecretsCache hostSecrets = new SecretsCache(StoragePaths.SERVER_SECRETS_FILE);

	/** The issued secret presented by a client, or null when no key was ever issued it. */
	public static IssuedSecret getHostSecret(String secret) {
		return hostSecrets.bySecret(secret);
	}

	public static void saveHostSecret(String playerId, Secrets.Secret secret, String playerName) {
		hostSecrets.save(playerId, secret, playerName);
	}
}
