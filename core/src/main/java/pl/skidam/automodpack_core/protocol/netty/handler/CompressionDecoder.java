package pl.skidam.automodpack_core.protocol.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

import java.util.List;

/**
 * Generic compression decoder that uses a CompressionCodec for decoding.
 * The specific compression algorithm is determined by the codec instance provided.
 */
public class CompressionDecoder extends ByteToMessageDecoder {

    private CompressionCodec codec;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 8) {
            return;
        }

        var comp = ctx.channel().attr(NettyServer.COMPRESSION_TYPE).get();
        codec = CompressionFactory.getCodec(comp);

        in.markReaderIndex();
        int compressedLength = in.readInt();
        int originalLength = in.readInt();

        // Validate lengths
        if (compressedLength < 0 || originalLength < 0) {
            throw new IllegalArgumentException("Invalid compressed or original length");
        }

        if (compressedLength > NetUtils.MAX_CHUNK_SIZE || originalLength > NetUtils.MAX_CHUNK_SIZE) {
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
