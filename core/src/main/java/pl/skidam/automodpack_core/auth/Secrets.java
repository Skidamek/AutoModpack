package pl.skidam.automodpack_core.auth;

import pl.skidam.automodpack_core.netty.NetUtils;

import java.net.SocketAddress;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Secrets {
    public record Secret(String secret, String fingerprint, Long timestamp) { }

    public static Secret generateSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32]; // 32 bytes = 256 bits
        random.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String fingerprint = generateFingerprint(secret);
        if (secret == null || fingerprint == null)
            return null;

        long timestamp = System.currentTimeMillis() / 1000;

        return new Secret(secret, fingerprint, timestamp);
    }

    private static String generateFingerprint(String secret) {
        try {
            X509Certificate cert = hostServer.getCert();
            if (cert == null)
                return null;
            return NetUtils.getFingerprint(cert, secret);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean isSecretValid(String secretStr, SocketAddress address) {
        if (!serverConfig.validateSecrets)
            return true;

        var playerSecretPair = SecretsStore.getHostSecret(secretStr);
        if (playerSecretPair == null)
            return false;

        Secret secret = playerSecretPair.getValue();
        if (secret == null)
            return false;

        String playerUuid = playerSecretPair.getKey();
        if (!GAME_CALL.canPlayerJoin(address, playerUuid)) // check if associated player is still whitelisted
            return false;

        long secretLifetime = serverConfig.secretLifetime * 3600; // in seconds
        long currentTime = System.currentTimeMillis() / 1000;

        boolean valid = secret.timestamp() + secretLifetime > currentTime;

        if (!valid)
            return false;

        return true;
    }
}
