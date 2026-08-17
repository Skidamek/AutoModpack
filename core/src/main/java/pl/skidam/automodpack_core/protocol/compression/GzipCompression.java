package pl.skidam.automodpack_core.protocol.compression;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * GZIP compression codec implementation.
 */
public class GzipCompression implements CompressionCodec {

	@Override
	public byte[] compress(byte[] input) throws IOException {
		byte[] output = new byte[maxCompressedLength(input.length)];
		int compressedLength = compress(input, 0, input.length, output, 0);
		return Arrays.copyOf(output, compressedLength);
	}

	@Override
	public int compress(byte[] input, int offset, int length, byte[] output, int outputOffset) throws IOException {
		if (offset < 0 || length < 0 || offset > input.length - length) throw new IndexOutOfBoundsException("Invalid compression input range");
		if (outputOffset < 0 || outputOffset > output.length) throw new IndexOutOfBoundsException("Invalid compression output offset");
		GzipOutputBuffer outputBuffer = new GzipOutputBuffer(output, outputOffset);
		try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputBuffer)) {
			gzipOutputStream.write(input, offset, length);
		} catch (Exception e) {
			throw new IOException("Failed to compress data with GZIP", e);
		}
		return outputBuffer.size();
	}

	@Override
	public byte[] decompress(byte[] compressed, int originalLength) throws IOException {
		return decompress(compressed, 0, compressed.length, originalLength);
	}

	@Override
	public byte[] decompress(byte[] compressedBuffer, int offset, int length, int originalLength) throws IOException {
		byte[] output = new byte[originalLength];
		decompress(compressedBuffer, offset, length, originalLength, output, 0);
		return output;
	}

	@Override
	public int decompress(byte[] compressedBuffer, int offset, int length, int originalLength, byte[] output, int outputOffset) throws IOException {
		if (offset < 0 || length < 0 || offset > compressedBuffer.length - length) throw new IndexOutOfBoundsException("Invalid decompression input range");
		if (originalLength < 0 || outputOffset < 0 || outputOffset > output.length - originalLength) throw new IndexOutOfBoundsException("Invalid decompression output range");
		try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(compressedBuffer, offset, length);
				GZIPInputStream gzipInputStream = new GZIPInputStream(byteArrayInputStream)) {
			int totalRead = 0;
			while (totalRead < originalLength) {
				int bytesRead = gzipInputStream.read(output, outputOffset + totalRead, originalLength - totalRead);
				if (bytesRead == -1) break;
				if (bytesRead == 0) continue;
				totalRead += bytesRead;
			}
			if (totalRead != originalLength || gzipInputStream.read() != -1) throw new IOException("Decompressed length does not match expected length (" + originalLength + ")");
			return totalRead;
		} catch (Exception e) {
			throw new IOException("Failed to decompress data with GZIP", e);
		}
	}

	@Override
	public int maxCompressedLength(int originalLength) {
		if (originalLength < 0) throw new IllegalArgumentException("Original length cannot be negative");
		return Math.addExact(originalLength, Math.addExact(originalLength / 16, 80));
	}

	@Override
	public CompressionType getCompressionType() {
		return CompressionType.GZIP;
	}

	private static final class GzipOutputBuffer extends OutputStream {
		private final byte[] output;
		private final int start;
		private int position;

		private GzipOutputBuffer(byte[] output, int offset) {
			this.output = output;
			this.start = offset;
			this.position = offset;
		}

		@Override
		public void write(int value) throws IOException {
			if (position == output.length) throw new IOException("GZIP output buffer is too small");
			output[position++] = (byte) value;
		}

		@Override
		public void write(byte[] input, int offset, int length) throws IOException {
			if (length < 0 || offset < 0 || offset > input.length - length) throw new IndexOutOfBoundsException("Invalid GZIP output range");
			if (length > output.length - position) throw new IOException("GZIP output buffer is too small");
			System.arraycopy(input, offset, output, position, length);
			position += length;
		}

		private int size() {
			return position - start;
		}
	}
}
