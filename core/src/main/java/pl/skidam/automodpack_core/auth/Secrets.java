package pl.skidam.automodpack_core.auth;

import pl.skidam.automodpack_core.utils.TimedSet;
import pl.skidam.automodpack_core.security.SharedSecurityPaths;

import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.Base64;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Secrets {
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
            return "Secret{" +
                    "timestamp=" + timestamp +
                    '}';
        }
    }

    public static Secret generateSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32]; // 32 bytes = 256 bits
        random.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (secret == null)
            return null;

        long timestamp = System.currentTimeMillis() / 1000;

        return new Secret(secret, timestamp);
    }

    // Cache of recently validated secrets to avoid repeated lookups for performance
    private static final TimedSet<String> cachedValidSecrets = new TimedSet<>(3500);

    public static boolean isSecretValid(String secretStr, SocketAddress address) {
        if (serverConfig == null)
            return false;
        if (!serverConfig.validateSecrets)
            return true;

        SharedSecurityPaths activeSharedSecurityPaths = sharedSecurityPaths;
        boolean shared = activeSharedSecurityPaths != null && activeSharedSecurityPaths.enabled();
        if (!shared && cachedValidSecrets.contains(secretStr))
            return true;

        final SharedSecretsStore.HostSecretRecord record;
        try {
            record = SecretsStore.findHostSecret(secretStr);
        } catch (Exception exception) {
            boolean failClosed = !shared || activeSharedSecurityPaths.failClosed();
            LOGGER.warn("Shared security validation failed {}: {}", failClosed ? "closed" : "open", exception.getMessage());
            return !failClosed;
        }
        if (record == null)
            return false;

        long currentTime = System.currentTimeMillis() / 1000;
        if (record.expiresAt() <= currentTime)
            return false;

        if (!shared || activeSharedSecurityPaths.authorizationMode() == SharedSecurityPaths.AuthorizationMode.HOST_RECHECK) {
            if (!GAME_CALL.isPlayerAuthorized(address, record.playerUuid())) {
                return false;
            }
        }

        // Shared mode deliberately performs a fresh locked read for every
        // primary authentication, so revocation/replacement is observed.
        if (!shared) {
            cachedValidSecrets.add(secretStr);
        }

        return true;
    }
}
