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
	public int compress(byte[] input, int offset, int length, byte[] output, int outputOffset) {
		if (offset < 0 || length < 0 || offset > input.length - length) throw new IndexOutOfBoundsException("Invalid compression input range");
		if (outputOffset < 0 || outputOffset > output.length - length) throw new IllegalArgumentException("Compression output buffer is too small");
		System.arraycopy(input, offset, output, outputOffset, length);
		return length;
	}

	@Override
	public int decompress(byte[] compressedBuffer, int offset, int length, int originalLength, byte[] output, int outputOffset) {
		if (offset < 0 || length < 0 || offset > compressedBuffer.length - length) throw new IndexOutOfBoundsException("Invalid decompression input range");
		if (length != originalLength) throw new IllegalArgumentException("Uncompressed length does not match expected length");
		if (outputOffset < 0 || outputOffset > output.length - length) throw new IllegalArgumentException("Decompression output buffer is too small");
		System.arraycopy(compressedBuffer, offset, output, outputOffset, length);
		return length;
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
