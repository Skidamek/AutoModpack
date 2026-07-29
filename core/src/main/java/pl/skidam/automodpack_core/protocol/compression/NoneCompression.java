package pl.skidam.automodpack_core.protocol.compression;

import java.util.Arrays;

/**
 * None compression codec implementation.
 * Input == Output (no compression).
 */
public class NoneCompression implements CompressionCodec {

	@Override
	public byte[] compress(byte[] input) {
		return input;
	}

	@Override
	public byte[] decompress(byte[] compressed, int originalLength) {
		if (compressed.length != originalLength) throw new IllegalArgumentException("Uncompressed length does not match expected length");
		return compressed;
	}

	@Override
	public byte[] decompress(byte[] compressedBuffer, int offset, int length, int originalLength) {
		if (length != originalLength) throw new IllegalArgumentException("Uncompressed length does not match expected length");
		return Arrays.copyOfRange(compressedBuffer, offset, offset + length);
	}

	@Override
	public int maxCompressedLength(int originalLength) {
		if (originalLength < 0) throw new IllegalArgumentException("Original length cannot be negative");
		return originalLength;
	}

	@Override
	public CompressionType getCompressionType() {
		return CompressionType.NONE;
	}
}
