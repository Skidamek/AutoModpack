package pl.skidam.automodpack_core.protocol.compression;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import io.airlift.compress.Compressor;
import io.airlift.compress.Decompressor;

final class AirliftCompressionCodec implements CompressionCodec {
	private final CompressionType compressionType;
	private final Compressor compressor;
	private final Decompressor decompressor;

	AirliftCompressionCodec(CompressionType compressionType, Compressor compressor, Decompressor decompressor) {
		this.compressionType = compressionType;
		this.compressor = compressor;
		this.decompressor = decompressor;
	}

	@Override
	public byte[] compress(byte[] input) throws IOException {
		try {
			byte[] output = new byte[compressor.maxCompressedLength(input.length)];
			int compressedLength = compress(input, 0, input.length, output, 0);
			return Arrays.copyOf(output, compressedLength);
		} catch (RuntimeException e) {
			throw new IOException("Failed to compress with " + compressionType, e);
		}
	}

	@Override
	public int compress(byte[] input, int offset, int length, byte[] output, int outputOffset) throws IOException {
		try {
			return compressor.compress(input, offset, length, output, outputOffset, output.length - outputOffset);
		} catch (RuntimeException e) {
			throw new IOException("Failed to compress with " + compressionType, e);
		}
	}

	@Override
	public int compress(ByteBuffer input, ByteBuffer output) throws IOException {
		int outputPosition = output.position();
		try {
			compressor.compress(input, output);
			return output.position() - outputPosition;
		} catch (RuntimeException e) {
			throw new IOException("Failed to compress with " + compressionType, e);
		}
	}

	@Override
	public byte[] decompress(byte[] compressed, int originalLength) throws IOException {
		return decompress(compressed, 0, compressed.length, originalLength);
	}

	@Override
	public byte[] decompress(byte[] compressedBuffer, int offset, int length, int originalLength) throws IOException {
		try {
			byte[] output = new byte[originalLength];
			decompress(compressedBuffer, offset, length, originalLength, output, 0);
			return output;
		} catch (RuntimeException e) {
			throw new IOException("Failed to decompress with " + compressionType, e);
		}
	}

	@Override
	public int decompress(byte[] compressedBuffer, int offset, int length, int originalLength, byte[] output, int outputOffset) throws IOException {
		try {
			int decompressedLength = decompressor.decompress(compressedBuffer, offset, length, output, outputOffset, originalLength);
			if (decompressedLength != originalLength) throw new IOException("Unexpected decompressed length: " + decompressedLength + " (expected " + originalLength + ")");
			return decompressedLength;
		} catch (RuntimeException e) {
			throw new IOException("Failed to decompress with " + compressionType, e);
		}
	}

	@Override
	public int maxCompressedLength(int originalLength) {
		if (originalLength < 0) throw new IllegalArgumentException("Original length cannot be negative");
		return compressor.maxCompressedLength(originalLength);
	}

	@Override
	public CompressionType getCompressionType() {
		return compressionType;
	}
}
