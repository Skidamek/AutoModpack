package pl.skidam.automodpack_core.protocol.compression;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAX_CHUNK_SIZE;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

class CompressionCodecTest {

	@Test
	void codecsRoundTripMaxFramesAndRanges() throws IOException {
		byte[] repetitive = new byte[MAX_CHUNK_SIZE];
		Arrays.fill(repetitive, (byte) 0x5A);
		byte[] random = new byte[MAX_CHUNK_SIZE];
		new Random(0xA170).nextBytes(random);

		for (CompressionType type : CompressionType.values()) {
			CompressionCodec codec = CompressionFactory.createCodec(type);
			assertRoundTrip(codec, repetitive);
			assertRoundTrip(codec, random);
		}
	}

	@Test
	void factoryCreatesIndependentCodecInstances() {
		for (CompressionType type : CompressionType.values()) {
			assertNotSame(CompressionFactory.createCodec(type), CompressionFactory.createCodec(type));
		}
	}

	@Test
	void snappyUsesCodecSpecificExpansionBound() {
		int oldFixedLimit = MAX_CHUNK_SIZE + 8192;
		assertTrue(CompressionFactory.createCodec(CompressionType.SNAPPY).maxCompressedLength(MAX_CHUNK_SIZE) > oldFixedLimit);
	}

	@Test
	void noneRejectsMismatchedLengths() {
		CompressionCodec codec = CompressionFactory.createCodec(CompressionType.NONE);
		assertThrows(IllegalArgumentException.class, () -> codec.decompress(new byte[4], 3));
	}

	@Test
	void compressionTypeRejectsUnknownWireId() {
		assertThrows(IllegalArgumentException.class, () -> CompressionType.fromWireId((byte) 0x7F));
	}

	private static void assertRoundTrip(CompressionCodec codec, byte[] input) throws IOException {
		byte[] compressed = codec.compress(input);
		assertTrue(compressed.length <= codec.maxCompressedLength(input.length), () -> codec.getCompressionType() + " exceeded its advertised bound");
		assertArrayEquals(input, codec.decompress(compressed, input.length));

		byte[] rangedBuffer = new byte[compressed.length + 8];
		System.arraycopy(compressed, 0, rangedBuffer, 4, compressed.length);
		assertArrayEquals(input, codec.decompress(rangedBuffer, 4, compressed.length, input.length));
	}
}
