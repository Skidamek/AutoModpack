package pl.skidam.automodpack_core.protocol.compression;

import java.io.IOException;

/**
 * Interface for compression/decompression operations.
 * Implementations should only be loaded when the specific compression type is needed.
 */
public interface CompressionCodec {

    /**
     * Compresses the input data.
     *
     * @param input the data to compress
     * @return the compressed data
     * @throws IOException if compression fails
     */
    byte[] compress(byte[] input) throws IOException;

    /**
     * Decompresses the compressed data.
     *
     * @param compressed the compressed data
     * @param originalLength the expected length of the decompressed data
     * @return the decompressed data
     * @throws IOException if decompression fails
     */
    byte[] decompress(byte[] compressed, int originalLength) throws IOException;

    /**
     * Gets the compression type identifier for this codec.
     *
     * @return the compression type constant from NetUtils
     */
    byte getCompressionType();
}
