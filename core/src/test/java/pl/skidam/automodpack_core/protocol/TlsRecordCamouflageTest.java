package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class TlsRecordCamouflageTest {
	@Test
	void encryptsRecordHeadersAndRoundTripsAcrossSplitBuffers() throws Exception {
		byte[] transportSecret = new byte[16];
		for (int index = 0; index < transportSecret.length; index++) transportSecret[index] = (byte) index;
		byte[] records = {
				0x17, 0x03, 0x03, 0x00, 0x05, 1, 2, 3, 4, 5,
				0x17, 0x03, 0x03, 0x00, 0x03, 6, 7, 8
		};
		TlsRecordCamouflage.Pair client = TlsRecordCamouflage.create(transportSecret, true);
		TlsRecordCamouflage.Pair server = TlsRecordCamouflage.create(transportSecret, false);

		ByteBuffer wire = ByteBuffer.allocate(records.length + 64);
		client.outbound().encode(ByteBuffer.wrap(records, 0, 3), wire);
		client.outbound().encode(ByteBuffer.wrap(records, 3, records.length - 3), wire);
		wire.flip();

		// The frame length stays a clear VarInt21 over the first record: 3-byte frame header plus 5-byte record header plus 5-byte payload.
		assertEquals((byte) 0x8A, wire.get(0));
		assertEquals((byte) 0x80, wire.get(1));
		assertEquals(0, wire.get(2));
		assertNotEquals(records[0], wire.get(3), "the record header must be masked");
		assertNotEquals(records[3], wire.get(6));
		assertArrayEquals(Arrays.copyOfRange(records, 5, 10), Arrays.copyOfRange(wire.array(), 8, 13), "the record payload must stay untouched");
		assertArrayEquals(Arrays.copyOfRange(records, 15, 18), Arrays.copyOfRange(wire.array(), 21, 24), "the second record payload must stay untouched");

		byte[] wireBytes = Arrays.copyOfRange(wire.array(), 0, wire.limit());
		ByteBuffer plain = ByteBuffer.allocate(records.length);
		server.inbound().decode(ByteBuffer.wrap(wireBytes, 0, 4), plain);
		server.inbound().decode(ByteBuffer.wrap(wireBytes, 4, wireBytes.length - 4), plain);
		plain.flip();
		byte[] roundTrip = new byte[plain.remaining()];
		plain.get(roundTrip);
		assertArrayEquals(records, roundTrip);
	}

	@Test
	void rejectsFramesWhosePayloadDisagreesWithTheMaskedHeader() throws Exception {
		byte[] transportSecret = new byte[16];
		for (int index = 0; index < transportSecret.length; index++) transportSecret[index] = (byte) (16 - index);
		byte[] record = {0x17, 0x03, 0x03, 0x00, 0x05, 1, 2, 3, 4, 5};
		TlsRecordCamouflage.Pair client = TlsRecordCamouflage.create(transportSecret, true);
		TlsRecordCamouflage.Pair server = TlsRecordCamouflage.create(transportSecret, false);

		ByteBuffer wire = ByteBuffer.allocate(64);
		client.outbound().encode(ByteBuffer.wrap(record), wire);
		wire.flip();
		byte[] corrupted = Arrays.copyOfRange(wire.array(), 0, wire.limit());
		corrupted[corrupted.length - 1] ^= 0x01;

		assertThrows(IOException.class, () -> server.inbound().decode(ByteBuffer.wrap(corrupted), ByteBuffer.allocate(corrupted.length)));
	}
}
