package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class MinecraftFrameCamouflageTest {
	@Test
	void masksFixedVarInt21HeadersAndRoundTripsSplitFrames() throws Exception {
		byte[] seed = new byte[32];
		for (int index = 0; index < seed.length; index++) seed[index] = (byte) index;
		byte[] first = new byte[17_000];
		byte[] second = {6, 7, 8, 9};
		for (int index = 0; index < first.length; index++) first[index] = (byte) index;

		MinecraftFrameCamouflage.Pair client = MinecraftFrameCamouflage.fromSessionSeed(seed, true);
		MinecraftFrameCamouflage.Pair server = MinecraftFrameCamouflage.fromSessionSeed(seed, false);
		ByteBuffer firstWire = ByteBuffer.allocate(first.length + MinecraftFrameCamouflage.HEADER_LENGTH);
		ByteBuffer secondWire = ByteBuffer.allocate(second.length + MinecraftFrameCamouflage.HEADER_LENGTH);
		client.outbound().encode(ByteBuffer.wrap(first), firstWire);
		client.outbound().encode(ByteBuffer.wrap(second), secondWire);
		firstWire.flip();
		secondWire.flip();

		assertNotEquals((byte) 0x88, firstWire.get(0));
		assertArrayEquals(first, Arrays.copyOfRange(firstWire.array(), MinecraftFrameCamouflage.HEADER_LENGTH, firstWire.limit()));
		assertArrayEquals(second, Arrays.copyOfRange(secondWire.array(), MinecraftFrameCamouflage.HEADER_LENGTH, secondWire.limit()));

		byte[] wire = new byte[firstWire.remaining() + secondWire.remaining()];
		firstWire.get(wire, 0, firstWire.remaining());
		secondWire.get(wire, firstWire.position(), secondWire.remaining());
		ByteBuffer decoded = ByteBuffer.allocate(wire.length);
		server.inbound().decode(ByteBuffer.wrap(wire, 0, 2), decoded);
		server.inbound().decode(ByteBuffer.wrap(wire, 2, wire.length - 2), decoded);
		decoded.flip();
		byte[] expected = new byte[first.length + second.length];
		System.arraycopy(first, 0, expected, 0, first.length);
		System.arraycopy(second, 0, expected, first.length, second.length);
		byte[] actual = new byte[decoded.remaining()];
		decoded.get(actual);
		assertArrayEquals(expected, actual);
	}
}
