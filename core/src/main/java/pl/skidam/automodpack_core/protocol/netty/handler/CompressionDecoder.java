package pl.skidam.automodpack_core.protocol.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;

import java.util.List;

/**
 * Generic compression decoder that uses a CompressionCodec for decoding.
 * The specific compression algorithm is determined by the codec instance provided.
 */
public class CompressionDecoder extends ByteToMessageDecoder {

    private final CompressionCodec codec;

    /**
     * Creates a new compression decoder with the specified codec.
     *
     * @param codec the compression codec to use for decoding
     */
    public CompressionDecoder(CompressionCodec codec) {
        if (codec == null) {
            throw new IllegalArgumentException("Compression codec cannot be null");
        }
        this.codec = codec;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 8) {
            return;
        }

        in.markReaderIndex();
        int compressedLength = in.readInt();
        int originalLength = in.readInt();

        // Validate lengths
        if (compressedLength < 0 || originalLength < 0) {
            throw new IllegalArgumentException("Invalid compressed or original length");
        }

        if (originalLength > NetUtils.CHUNK_SIZE) {
            throw new IllegalArgumentException("Original length exceeds maximum packet size");
        }

        // Check if we have enough data
        if (in.readableBytes() < compressedLength) {
            in.resetReaderIndex();
            return;
        }

        // Read compressed data
        byte[] compressed = new byte[compressedLength];
        in.readBytes(compressed);

        // Decompress using the codec
        byte[] decompressed = codec.decompress(compressed, originalLength);

        // Create output buffer with decompressed data
        ByteBuf decompressedBuf = ctx.alloc().buffer(originalLength);
        decompressedBuf.writeBytes(decompressed);
        out.add(decompressedBuf);
    }

    /**
     * Gets the compression codec used by this decoder.
     *
     * @return the compression codec
     */
    public CompressionCodec getCodec() {
        return codec;
    }
}
