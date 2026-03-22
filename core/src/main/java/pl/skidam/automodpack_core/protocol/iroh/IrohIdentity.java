package pl.skidam.automodpack_core.protocol.iroh;

import dev.iroh.IrohNode;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class IrohIdentity {
    private static final HexFormat HEX = HexFormat.of();
    private static final int SECRET_KEY_SIZE = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private IrohIdentity() {
    }

    public static String toHex(byte[] endpointId) {
        return HEX.formatHex(endpointId);
    }

    public static byte[] fromHex(String endpointId) {
        return HEX.parseHex(endpointId);
    }

    public static byte[] deriveEndpointId(byte[] secretKey) {
        return IrohNode.deriveEndpointId(secretKey);
    }

    public static byte[] loadOrCreateEndpointId(Path keyFile) throws IOException {
        return deriveEndpointId(loadOrCreateSecret(keyFile));
    }

    public static byte[] loadSecret(Path keyFile) throws IOException {
        byte[] secret = Files.readAllBytes(keyFile);
        if (secret.length != SECRET_KEY_SIZE) {
            throw new IOException("Invalid iroh secret key length in " + keyFile + ": expected "
                    + SECRET_KEY_SIZE + " bytes but found " + secret.length);
        }
        return secret;
    }

    public static byte[] createAndPersistSecret(Path keyFile) throws IOException {
        byte[] secret = new byte[SECRET_KEY_SIZE];
        SECURE_RANDOM.nextBytes(secret);
        SmartFileUtils.createParentDirs(keyFile);
        Files.write(keyFile, secret);
        return secret;
    }

    public static byte[] loadOrCreateSecret(Path keyFile) throws IOException {
        if (Files.exists(keyFile)) {
            return loadSecret(keyFile);
        }
        return createAndPersistSecret(keyFile);
    }

    public static String canonicalServerKey(InetSocketAddress address) {
        return address.getHostString().toLowerCase() + ":" + address.getPort();
    }
}
