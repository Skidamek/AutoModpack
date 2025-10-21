package pl.skidam.automodpack_core.protocol.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static pl.skidam.automodpack_core.protocol.NetUtils.COMPRESSION_GZIP;

/**
 * GZIP compression codec implementation.
 */
public class GzipCompression implements CompressionCodec {

    @Override
    public boolean isInitialized() {
        return true;
    }

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
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(compressed);
             GZIPInputStream gzipInputStream = new GZIPInputStream(byteArrayInputStream)) {

            byte[] decompressed = new byte[originalLength];
            int totalRead = 0;
            int bytesRead;

            while (totalRead < originalLength && (bytesRead = gzipInputStream.read(decompressed, totalRead, originalLength - totalRead)) != -1) {
                totalRead += bytesRead;
            }

            if (totalRead != originalLength) {
                throw new IOException("Decompressed length (" + totalRead + ") does not match expected length (" + originalLength + ")");
            }

            return decompressed;
        } catch (Exception e) {
            throw new IOException("Failed to decompress data with GZIP", e);
        }
    }

    @Override
    public byte getCompressionType() {
        return COMPRESSION_GZIP;
    }
}
