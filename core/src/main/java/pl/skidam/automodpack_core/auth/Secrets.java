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
			// The raw secret is a bearer credential; logging a Secret object must never print it.
			return "Secret{secret=<redacted>, timestamp=" + timestamp + '}';
		}
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
		if (playerSecretPair == null) {
			LOGGER.warn("Rejecting an unknown secret from {}", address);
			return false;
		}

		IssuedSecret issued = playerSecretPair.getValue();
		if (issued == null || issued.name() == null || issued.name().isBlank() || issued.timestamp() == null) {
			LOGGER.warn("Rejecting a secret from {} that is not bound to a player identity (stale entry from an older AutoModpack version)", address);
			return false;
		}

		if (!GAME_CALL.isPlayerAuthorized(address, playerSecretPair.getKey(), issued.name())) { // check if the player the secret was issued to is still authorized
			LOGGER.warn("Rejecting a secret from {}: {} is no longer authorized - make sure they are whitelisted", address, issued.name());
			return false;
		}

		long secretLifetime = serverConfig.secretLifetime * 3600; // in seconds
		long currentTime = System.currentTimeMillis() / 1000;

		boolean valid = issued.timestamp() + secretLifetime > currentTime;

		if (!valid) {
			LOGGER.warn("Rejecting an expired secret from {}", address);
			return false;
		}

		cachedValidSecrets.add(secretStr);

		return true;
	}
}
