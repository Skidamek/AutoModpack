package pl.skidam.automodpack_core.protocol.compression;

import java.io.IOException;

/**
 * Interface for compression/decompression operations.
 * Implementations should only be loaded when the specific compression type is needed.
 */
public interface CompressionCodec {

    boolean isInitialized();

    /**
     * Compresses the input data.
     *
     * @param input the data to compress
     * @return the compressed data
     * @throws IOException if compression fails
     */
    byte[] compress(byte[] input) throws IOException;

    /**
     * Compresses a specific range of the input data.
     *
     * <p>The default implementation copies the requested range into a standalone array for
     * backward compatibility. Codecs that support ranged compression should override this
     * to avoid the extra source-buffer copy.
     *
     * @param input the source buffer
     * @param offset the start offset of the input range
     * @param length the length of the input range
     * @return the compressed data
     * @throws IOException if compression fails
     */
    default byte[] compress(byte[] input, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > input.length - length) {
            throw new IOException("Invalid compression range");
        }
        if (offset == 0 && length == input.length) {
            return compress(input);
        }
        byte[] slice = new byte[length];
        System.arraycopy(input, offset, slice, 0, length);
        return compress(slice);
    }

    /**
     * Decompresses the compressed data.
     * <p>
     * Note: This legacy method assumes the entire array is the compressed payload.
     *
     * @param compressed the compressed data
     * @param originalLength the expected length of the decompressed data
     * @return the decompressed data
     * @throws IOException if decompression fails
     */
    byte[] decompress(byte[] compressed, int originalLength) throws IOException;

    /**
     * Decompresses a specific range of the compressed data buffer.
     * <p>
     * This method allows zero-copy processing of input buffers (e.g. reused network buffers).
     *
     * @param compressedBuffer the buffer containing compressed data
     * @param offset the start offset of the compressed data
     * @param length the length of the compressed data
     * @param originalLength the expected length of the decompressed data
     * @return the decompressed data
     * @throws IOException if decompression fails
     */
    default byte[] decompress(byte[] compressedBuffer, int offset, int length, int originalLength) throws IOException {
        // Default implementation for backward compatibility or simple codecs:
        // Create a slice and delegate to the simple method.
        // Subclasses (GZIP/Zstd) should override this to avoid the copy.
        if (offset == 0 && length == compressedBuffer.length) {
            return decompress(compressedBuffer, originalLength);
        }
        byte[] slice = new byte[length];
        System.arraycopy(compressedBuffer, offset, slice, 0, length);
        return decompress(slice, originalLength);
    }

    /**
     * Gets the compression type identifier for this codec.
     *
     * @return the compression type constant from NetUtils
     */
    byte getCompressionType();
}
