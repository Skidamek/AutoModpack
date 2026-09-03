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
	void encryptsRecordHeadersAndRoundTripsAcrossSplitBuffers() throws Exception {
		byte[] transportSecret = new byte[16];
		for (int index = 0; index < transportSecret.length; index++) transportSecret[index] = (byte) index;
		byte[] records = new byte[10 + 5 + 123];
		records[0] = 0x17;
		records[1] = 0x03;
		records[2] = 0x03;
		// 5-byte payload, frame length 10: a one-byte canonical VarInt.
		records[3] = 0x00;
		records[4] = 0x05;
		records[5] = 1;
		records[6] = 2;
		records[7] = 3;
		records[8] = 4;
		records[9] = 5;
		// 123-byte payload, frame length 128: a two-byte canonical VarInt.
		records[10] = 0x17;
		records[11] = 0x03;
		records[12] = 0x03;
		records[13] = 0x00;
		records[14] = 0x7B;
		Arrays.fill(records, 15, records.length, (byte) 9);
		TlsRecordCamouflage.Pair client = TlsRecordCamouflage.create(transportSecret, true);
		TlsRecordCamouflage.Pair server = TlsRecordCamouflage.create(transportSecret, false);

		ByteBuffer wire = ByteBuffer.allocate(records.length + 64);
		client.outbound().encode(ByteBuffer.wrap(records, 0, 3), wire);
		client.outbound().encode(ByteBuffer.wrap(records, 3, records.length - 3), wire);
		wire.flip();

		assertEquals((byte) 0x0A, wire.get(0), "a ten-byte frame must carry a one-byte canonical VarInt");
		assertEquals((byte) 0x80, wire.get(11), "a 128-byte frame must carry a two-byte canonical VarInt");
		assertEquals((byte) 0x01, wire.get(12));
		assertFalse(Arrays.equals(Arrays.copyOfRange(wire.array(), 1, 6), Arrays.copyOfRange(records, 0, 5)), "the record header must be masked");
		assertFalse(Arrays.equals(Arrays.copyOfRange(wire.array(), 13, 18), Arrays.copyOfRange(records, 10, 15)), "the second record header must be masked");
		assertArrayEquals(Arrays.copyOfRange(records, 5, 10), Arrays.copyOfRange(wire.array(), 6, 11), "the record payload must stay untouched");
		assertArrayEquals(Arrays.copyOfRange(records, 15, records.length), Arrays.copyOfRange(wire.array(), 18, 18 + records.length - 15), "the second record payload must stay untouched");

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

	@Test
	void rejectsFrameLengthsBeyondTheVarIntWidth() throws Exception {
		TlsRecordCamouflage.Pair server = TlsRecordCamouflage.create(new byte[16], false);
		byte[] oversized = {(byte) 0xFF, (byte) 0xFF, 0x7F};
		assertThrows(IOException.class, () -> server.inbound().decode(ByteBuffer.wrap(oversized), ByteBuffer.allocate(64)));
		byte[] unterminated = {(byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00};
		assertThrows(IOException.class, () -> server.inbound().decode(ByteBuffer.wrap(unterminated), ByteBuffer.allocate(64)));
	}
}
