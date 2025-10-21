package pl.skidam.automodpack_core.protocol.compression;

import static pl.skidam.automodpack_core.protocol.NetUtils.*;

public class CompressionFactory {

    /**
     * Gets a compression codec for the specified compression type.
     * The codec is lazily loaded and cached for subsequent use.
     *
     * @param compressionType the compression type constant from NetUtils
     * @return the compression codec
     * @throws IllegalArgumentException if the compression type is not supported
     */
    public static CompressionCodec getCodec(byte compressionType) {
       return switch (compressionType) {
            case COMPRESSION_ZSTD -> Zstd.CODEC;
            case COMPRESSION_GZIP -> Gzip.CODEC;
           case COMPRESSION_NONE -> None.CODEC;
            default -> throw new IllegalArgumentException("Unsupported compression type: " + compressionType);
        };
    }

    private static class Zstd {
        private static final ZstdCompression CODEC = new ZstdCompression();
    }

    private static class Gzip {
        private static final GzipCompression CODEC = new GzipCompression();
    }

    private static class None {
        private static final NoneCompression CODEC = new NoneCompression();
    }
}