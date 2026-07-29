package pl.skidam.automodpack_core.protocol.compression;

import java.io.IOException;
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
			int compressedLength = compressor.compress(input, 0, input.length, output, 0, output.length);
			return Arrays.copyOf(output, compressedLength);
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
			int decompressedLength = decompressor.decompress(compressedBuffer, offset, length, output, 0, output.length);
			if (decompressedLength != originalLength) {
				throw new IOException("Unexpected decompressed length: " + decompressedLength + " (expected " + originalLength + ")");
			}
			return output;
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
