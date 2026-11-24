package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class TlsRecordCamouflageTest {
	@Test
	void masksRecordHeadersWithZeroFramingAndRoundTripsAcrossSplitBuffers() throws Exception {
		byte[] transportSecret = new byte[16];
		for (int index = 0; index < transportSecret.length; index++) transportSecret[index] = (byte) index;
		byte[] records = new byte[10 + 10];
		records[0] = 0x17;
		records[1] = 0x03;
		records[2] = 0x03;
		records[3] = 0x00;
		records[4] = 0x05;
		records[5] = 1;
		records[6] = 2;
		records[7] = 3;
		records[8] = 4;
		records[9] = 5;
		// Identical five-byte header on the next record must produce a different wire mask.
		System.arraycopy(records, 0, records, 10, 10);

		TlsRecordCamouflage.Pair client = TlsRecordCamouflage.create(transportSecret, true);
		TlsRecordCamouflage.Pair server = TlsRecordCamouflage.create(transportSecret, false);

		ByteBuffer wire = ByteBuffer.allocate(records.length);
		client.outbound().encode(ByteBuffer.wrap(records, 0, 3), wire);
		client.outbound().encode(ByteBuffer.wrap(records, 3, records.length - 3), wire);
		wire.flip();

		assertEquals(records.length, wire.remaining(), "camouflage must add zero framing bytes");
		assertFalse(Arrays.equals(Arrays.copyOfRange(wire.array(), 0, 5), Arrays.copyOfRange(records, 0, 5)), "the first record header must be masked");
		assertFalse(Arrays.equals(Arrays.copyOfRange(wire.array(), 10, 15), Arrays.copyOfRange(records, 10, 15)), "the second record header must be masked");
		assertFalse(Arrays.equals(Arrays.copyOfRange(wire.array(), 0, 5), Arrays.copyOfRange(wire.array(), 10, 15)), "counter masking must differ across consecutive records");
		assertArrayEquals(Arrays.copyOfRange(records, 5, 10), Arrays.copyOfRange(wire.array(), 5, 10), "the first record payload must stay untouched");
		assertArrayEquals(Arrays.copyOfRange(records, 15, 20), Arrays.copyOfRange(wire.array(), 15, 20), "the second record payload must stay untouched");

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
	void rejectsCorruptedOrOversizedRecordHeaders() throws Exception {
		byte[] transportSecret = new byte[16];
		for (int index = 0; index < transportSecret.length; index++) transportSecret[index] = (byte) (16 - index);
		byte[] record = {0x17, 0x03, 0x03, 0x00, 0x05, 1, 2, 3, 4, 5};
		TlsRecordCamouflage.Pair client = TlsRecordCamouflage.create(transportSecret, true);
		TlsRecordCamouflage.Pair server = TlsRecordCamouflage.create(transportSecret, false);

		ByteBuffer wire = ByteBuffer.allocate(record.length);
		client.outbound().encode(ByteBuffer.wrap(record), wire);
		wire.flip();
		byte[] corrupted = Arrays.copyOfRange(wire.array(), 0, wire.limit());
		corrupted[0] ^= 0x40;

		assertThrows(IOException.class, () -> server.inbound().decode(ByteBuffer.wrap(corrupted), ByteBuffer.allocate(corrupted.length)));

		int tooLarge = TlsRecordCamouflage.MAX_RECORD_LENGTH + 1;
		byte[] oversized = {0x17, 0x03, 0x03, (byte) (tooLarge >>> 8), (byte) tooLarge};
		assertThrows(IOException.class, () -> client.outbound().encode(ByteBuffer.wrap(oversized), ByteBuffer.allocate(oversized.length)));
	}
}
