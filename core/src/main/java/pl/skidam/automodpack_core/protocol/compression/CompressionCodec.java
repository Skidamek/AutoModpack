package pl.skidam.automodpack_core.protocol.compression;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Interface for compression/decompression operations.
 * Implementations should only be loaded when the specific compression type is needed.
 */
public interface CompressionCodec {

	/**
	 * Compresses the input data.
	 *
	 * @param input
	 *            the data to compress
	 * @return the compressed data
	 * @throws IOException
	 *             if compression fails
	 */
	byte[] compress(byte[] input) throws IOException;

	/** Compresses a range directly into a caller-owned output buffer. */
	default int compress(byte[] input, int offset, int length, byte[] output, int outputOffset) throws IOException {
		if (offset < 0 || length < 0 || offset > input.length - length) throw new IndexOutOfBoundsException("Invalid compression input range");
		if (outputOffset < 0 || outputOffset > output.length) throw new IndexOutOfBoundsException("Invalid compression output offset");
		byte[] compressed = compress(offset == 0 && length == input.length ? input : Arrays.copyOfRange(input, offset, offset + length));
		if (compressed.length > output.length - outputOffset) throw new IOException("Compression output buffer is too small");
		System.arraycopy(compressed, 0, output, outputOffset, compressed.length);
		return compressed.length;
	}

	/** Compresses a ByteBuffer range directly into a caller-owned ByteBuffer. */
	default int compress(ByteBuffer input, ByteBuffer output) throws IOException {
		int inputLength = input.remaining();
		int outputOffset = output.position();
		if (output.remaining() < maxCompressedLength(inputLength)) throw new IOException("Compression output buffer is too small");
		if (input.hasArray() && output.hasArray()) {
			int compressedLength = compress(input.array(), input.arrayOffset() + input.position(), inputLength, output.array(), output.arrayOffset() + outputOffset);
			output.position(outputOffset + compressedLength);
			return compressedLength;
		}
		byte[] source = new byte[inputLength];
		input.duplicate().get(source);
		byte[] compressed = compress(source);
		output.put(compressed);
		return compressed.length;
	}

	/**
	 * Decompresses the compressed data.
	 * <p>
	 * Note: This legacy method assumes the entire array is the compressed payload.
	 *
	 * @param compressed
	 *            the compressed data
	 * @param originalLength
	 *            the expected length of the decompressed data
	 * @return the decompressed data
	 * @throws IOException
	 *             if decompression fails
	 */
	byte[] decompress(byte[] compressed, int originalLength) throws IOException;

	/**
	 * Decompresses a specific range of the compressed data buffer.
	 * <p>
	 * This method allows zero-copy processing of input buffers (e.g. reused network buffers).
	 *
	 * @param compressedBuffer
	 *            the buffer containing compressed data
	 * @param offset
	 *            the start offset of the compressed data
	 * @param length
	 *            the length of the compressed data
	 * @param originalLength
	 *            the expected length of the decompressed data
	 * @return the decompressed data
	 * @throws IOException
	 *             if decompression fails
	 */
	default byte[] decompress(byte[] compressedBuffer, int offset, int length, int originalLength) throws IOException {
		// Default implementation for backward compatibility or simple codecs:
		// Create a slice and delegate to the simple method.
		// Subclasses (GZIP/Zstd) should override this to avoid the copy.
		if (offset == 0 && length == compressedBuffer.length) return decompress(compressedBuffer, originalLength);
		byte[] slice = new byte[length];
		System.arraycopy(compressedBuffer, offset, slice, 0, length);
		return decompress(slice, originalLength);
	}

	/** Decompresses directly into a caller-owned output buffer. */
	default int decompress(byte[] compressedBuffer, int offset, int length, int originalLength, byte[] output, int outputOffset) throws IOException {
		if (offset < 0 || length < 0 || offset > compressedBuffer.length - length) throw new IndexOutOfBoundsException("Invalid decompression input range");
		if (originalLength < 0 || outputOffset < 0 || outputOffset > output.length - originalLength) throw new IndexOutOfBoundsException("Invalid decompression output range");
		byte[] decompressed = decompress(compressedBuffer, offset, length, originalLength);
		if (decompressed.length != originalLength) throw new IOException("Codec returned " + decompressed.length + " bytes for a frame that declares " + originalLength);
		System.arraycopy(decompressed, 0, output, outputOffset, originalLength);
		return originalLength;
	}

	int maxCompressedLength(int originalLength);

	CompressionType getCompressionType();
}
