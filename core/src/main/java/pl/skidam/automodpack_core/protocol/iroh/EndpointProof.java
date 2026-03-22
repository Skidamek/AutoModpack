package pl.skidam.automodpack_core.protocol.iroh;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

public final class EndpointProof {
    public static final int NONCE_LENGTH = 32;
    public static final int ENDPOINT_ID_LENGTH = 32;
    public static final int HASH_LENGTH = 32;
    public static final int TOTAL_LENGTH = NONCE_LENGTH + ENDPOINT_ID_LENGTH + HASH_LENGTH;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private EndpointProof() {
    }

    public static byte[] create(byte[] endpointId) {
        if (endpointId == null || endpointId.length != ENDPOINT_ID_LENGTH) {
            throw new IllegalArgumentException("Endpoint ID must be 32 bytes");
        }

        byte[] nonce = new byte[NONCE_LENGTH];
        SECURE_RANDOM.nextBytes(nonce);

        byte[] proof = new byte[TOTAL_LENGTH];
        System.arraycopy(nonce, 0, proof, 0, NONCE_LENGTH);
        System.arraycopy(endpointId, 0, proof, NONCE_LENGTH, ENDPOINT_ID_LENGTH);
        System.arraycopy(hash(nonce, endpointId), 0, proof, NONCE_LENGTH + ENDPOINT_ID_LENGTH, HASH_LENGTH);
        return proof;
    }

    public static byte[] verifyAndExtractEndpointId(byte[] proof) {
        if (proof == null || proof.length != TOTAL_LENGTH) {
            throw new IllegalArgumentException("Endpoint proof must be exactly " + TOTAL_LENGTH + " bytes");
        }

        byte[] nonce = Arrays.copyOfRange(proof, 0, NONCE_LENGTH);
        byte[] endpointId = Arrays.copyOfRange(proof, NONCE_LENGTH, NONCE_LENGTH + ENDPOINT_ID_LENGTH);
        byte[] expectedHash = Arrays.copyOfRange(proof, NONCE_LENGTH + ENDPOINT_ID_LENGTH, TOTAL_LENGTH);
        if (!Arrays.equals(expectedHash, hash(nonce, endpointId))) {
            throw new IllegalArgumentException("Endpoint proof hash mismatch");
        }
        return endpointId;
    }

    private static byte[] hash(byte[] nonce, byte[] endpointId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(nonce);
            digest.update(endpointId);
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute endpoint proof hash", e);
        }
    }
}
