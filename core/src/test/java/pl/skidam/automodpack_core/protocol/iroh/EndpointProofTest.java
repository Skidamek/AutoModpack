package pl.skidam.automodpack_core.protocol.iroh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EndpointProofTest {

    @Test
    void createsAndVerifiesProof() {
        byte[] endpointId = new byte[EndpointProof.ENDPOINT_ID_LENGTH];
        for (int i = 0; i < endpointId.length; i++) {
            endpointId[i] = (byte) (0x10 + i);
        }

        byte[] proof = EndpointProof.create(endpointId);

        assertArrayEquals(endpointId, EndpointProof.verifyAndExtractEndpointId(proof));
    }

    @Test
    void rejectsTamperedProof() {
        byte[] endpointId = new byte[EndpointProof.ENDPOINT_ID_LENGTH];
        byte[] proof = EndpointProof.create(endpointId);
        proof[proof.length - 1] ^= 0x01;

        assertThrows(IllegalArgumentException.class, () -> EndpointProof.verifyAndExtractEndpointId(proof));
    }
}
