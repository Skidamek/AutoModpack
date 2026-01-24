package pl.skidam.automodpack_core.protocol.compression;

import java.util.Arrays;

import static pl.skidam.automodpack_core.protocol.NetUtils.COMPRESSION_NONE;

/**
 * None compression codec implementation.
 * Input == Output (no compression).
 */
public class NoneCompression implements CompressionCodec {

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public byte[] compress(byte[] input) {
        return input;
    }

    @Override
    public byte[] decompress(byte[] compressed, int originalLength) {
        return compressed;
    }

    @Override
    public byte[] decompress(byte[] compressedBuffer, int offset, int length, int originalLength) {
        // For "None", we just return the slice of the buffer.
        // We must copy it because the caller expects a standalone array of size 'originalLength'.
        return Arrays.copyOfRange(compressedBuffer, offset, offset + length);
    }

    @Override
    public byte getCompressionType() {
        return COMPRESSION_NONE;
    }
}