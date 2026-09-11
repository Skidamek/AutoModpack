package pl.skidam.automodpack_core.auth;

import static pl.skidam.automodpack_core.Constants.*;

import java.net.SocketAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import pl.skidam.automodpack_core.utils.TimedSet;

public class Secrets {
	public static final int BYTE_LENGTH = 32;

	public static class Secret { // unfortunately has to be a class instead of record because of older gson version in 1.18 mc
		private String secret; // and these also can't be final
		private Long timestamp;

		public Secret(String secret, Long timestamp) {
			this.secret = secret;
			this.timestamp = timestamp;
		}

		public String secret() {
			return secret;
		}

		public byte[] secretBytes() {
			return Base64.getUrlDecoder().decode(secret);
		}

		public Long timestamp() {
			return timestamp;
		}

		@Override
		public String toString() {
			return "Secret{secret='" + secret + '\'' + ", timestamp=" + timestamp + '}';
		}
	}

	public static Secret anonymousSecret() {
		return new Secret(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[BYTE_LENGTH]), 0L);
	}

	public static Secret generateSecret() {
		SecureRandom random = new SecureRandom();
		byte[] bytes = new byte[BYTE_LENGTH];
		random.nextBytes(bytes);
		String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		long timestamp = System.currentTimeMillis() / 1000;

		return new Secret(secret, timestamp);
	}

	public static String normalizeProvisioningSecret(String secret) {
		if (secret == null || secret.isBlank()) return null;
		byte[] bytes;
		try {
			bytes = Base64.getUrlDecoder().decode(secret);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Bootstrap secret is not valid Base64URL", e);
		}
		if (bytes.length != BYTE_LENGTH) throw new IllegalArgumentException("Bootstrap secret must be " + BYTE_LENGTH + " bytes");
		if (isZeroed(bytes)) throw new IllegalArgumentException("Bootstrap secret cannot be the anonymous secret");
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static boolean isZeroed(byte[] bytes) {
		for (byte value : bytes) if (value != 0) return false;
		return true;
	}

	private static boolean isProvisioningSecret(String secretStr) {
		String expected = ProvisioningSecretStore.get();
		if (expected == null || expected.isBlank() || secretStr == null || secretStr.isBlank()) return false;
		try {
			byte[] presented = Base64.getUrlDecoder().decode(secretStr);
			byte[] configured = Base64.getUrlDecoder().decode(expected);
			return presented.length == BYTE_LENGTH && configured.length == BYTE_LENGTH && MessageDigest.isEqual(presented, configured);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	// Cache of recently validated secrets to avoid repeated lookups for performance
	private static final TimedSet<String> cachedValidSecrets = new TimedSet<>(3500);

	public static boolean isSecretValid(String secretStr, SocketAddress address) {
		if (!serverConfig.validateSecrets) return true;

		if (cachedValidSecrets.contains(secretStr)) return true;

		if (isProvisioningSecret(secretStr)) {
			cachedValidSecrets.add(secretStr);
			return true;
		}

		var playerSecretPair = SecretsStore.getHostSecret(secretStr);
		if (playerSecretPair == null) return false;

		Secret secret = playerSecretPair.getValue();
		if (secret == null) return false;

		String playerUuid = playerSecretPair.getKey();
		if (!GAME_CALL.isPlayerAuthorized(address, playerUuid)) // check if associated player is still whitelisted
			return false;

		long secretLifetime = serverConfig.secretLifetime * 3600; // in seconds
		long currentTime = System.currentTimeMillis() / 1000;

		boolean valid = secret.timestamp() + secretLifetime > currentTime;

		if (!valid) return false;

		cachedValidSecrets.add(secretStr);

		return true;
	}
}
