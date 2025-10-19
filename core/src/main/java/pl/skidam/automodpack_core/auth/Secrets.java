package pl.skidam.automodpack_core.auth;

import pl.skidam.automodpack_core.utils.TimedSet;

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
                    "secret='" + secret + '\'' +
                    ", timestamp=" + timestamp +
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
        if (!serverConfig.validateSecrets)
            return true;

        if (cachedValidSecrets.contains(secretStr))
            return true;

        var playerSecretPair = SecretsStore.getHostSecret(secretStr);
        if (playerSecretPair == null)
            return false;

        Secret secret = playerSecretPair.getValue();
        if (secret == null)
            return false;

        String playerUuid = playerSecretPair.getKey();
        if (!GAME_CALL.isPlayerAuthorized(address, playerUuid)) // check if associated player is still whitelisted
            return false;

        long secretLifetime = serverConfig.secretLifetime * 3600; // in seconds
        long currentTime = System.currentTimeMillis() / 1000;

        boolean valid = secret.timestamp() + secretLifetime > currentTime;

        if (!valid)
            return false;

        cachedValidSecrets.add(secretStr);

        return true;
    }
}
