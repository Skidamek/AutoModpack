package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class TlsRecordHeaderCamouflageTest {
	@Test
	void masksHeadersAndRoundTripsAcrossSplitBuffers() throws Exception {
		byte[] sessionSeed = new byte[32];
		for (int index = 0; index < sessionSeed.length; index++) sessionSeed[index] = (byte) index;
		byte[] records = {
				0x17, 0x03, 0x03, 0x00, 0x05, 1, 2, 3, 4, 5,
				0x17, 0x03, 0x03, 0x00, 0x03, 6, 7, 8
		};
		TlsRecordHeaderCamouflage.Pair client = TlsRecordHeaderCamouflage.fromSessionSeed(sessionSeed, true);
		TlsRecordHeaderCamouflage.Pair server = TlsRecordHeaderCamouflage.fromSessionSeed(sessionSeed, false);
		ByteBuffer encoded = ByteBuffer.allocate(records.length);
		client.outbound().transform(ByteBuffer.wrap(records, 0, 3), encoded);
		client.outbound().transform(ByteBuffer.wrap(records, 3, records.length - 3), encoded);
		encoded.flip();

		byte[] wire = new byte[encoded.remaining()];
		encoded.get(wire);
		assertNotEquals(records[0], wire[0]);
		assertArrayEquals(Arrays.copyOfRange(records, 5, 10), Arrays.copyOfRange(wire, 5, 10));
		assertArrayEquals(Arrays.copyOfRange(records, 15, 18), Arrays.copyOfRange(wire, 15, 18));

		ByteBuffer decoded = ByteBuffer.allocate(wire.length);
		server.inbound().transform(ByteBuffer.wrap(wire, 0, 4), decoded);
		server.inbound().transform(ByteBuffer.wrap(wire, 4, wire.length - 4), decoded);
		decoded.flip();
		byte[] roundTrip = new byte[decoded.remaining()];
		decoded.get(roundTrip);
		assertArrayEquals(records, roundTrip);
	}
}
