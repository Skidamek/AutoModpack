package pl.skidam.automodpack_core.auth;

import pl.skidam.automodpack_core.GlobalVariables;

import java.security.SecureRandom;
import java.util.Base64;

public class Secrets {
    public record Secret(String secret, Long timestamp) { }

    public static Secret generateSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32]; // 32 bytes = 256 bits
        random.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long timestamp = System.currentTimeMillis() / 1000;

        return new Secret(secret, timestamp);
    }

    public static boolean isSecretValid(String secretStr) {
        Secret secret = SecretsStore.getHostSecret(secretStr);
        if (secret == null)
            return false;

        long secretLifetime = GlobalVariables.serverConfig.secretLifetime * 3600; // in seconds
        long currentTime = System.currentTimeMillis() / 1000;

        return currentTime - secret.timestamp() < secretLifetime;
    }
}
