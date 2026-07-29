package pl.skidam.automodpack_core.protocol.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * GZIP compression codec implementation.
 */
public class GzipCompression implements CompressionCodec {

	@Override
	public byte[] compress(byte[] input) throws IOException {
		try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
				GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {
			gzipOutputStream.write(input);
			gzipOutputStream.finish();
			return byteArrayOutputStream.toByteArray();
		} catch (Exception e) {
			throw new IOException("Failed to compress data with GZIP", e);
		}
	}

	@Override
	public byte[] decompress(byte[] compressed, int originalLength) throws IOException {
		return decompress(compressed, 0, compressed.length, originalLength);
	}

	@Override
	public byte[] decompress(byte[] compressedBuffer, int offset, int length, int originalLength) throws IOException {
		try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(compressedBuffer, offset, length);
				GZIPInputStream gzipInputStream = new GZIPInputStream(byteArrayInputStream)) {

			byte[] decompressed = new byte[originalLength];
			int totalRead = 0;
			int bytesRead;

			while (totalRead < originalLength && (bytesRead = gzipInputStream.read(decompressed, totalRead, originalLength - totalRead)) != -1) {
				totalRead += bytesRead;
			}

			if (totalRead != originalLength || gzipInputStream.read() != -1) {
				throw new IOException("Decompressed length does not match expected length (" + originalLength + ")");
			}

			return decompressed;
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
}
