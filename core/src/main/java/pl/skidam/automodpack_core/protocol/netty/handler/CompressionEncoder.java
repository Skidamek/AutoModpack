package pl.skidam.automodpack_core.protocol.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;

/**
 * Generic compression encoder that uses a CompressionCodec for encoding.
 * The specific compression algorithm is determined by the codec instance provided.
 */
public class CompressionEncoder extends MessageToByteEncoder<ByteBuf> {

    private final CompressionCodec codec;

    /**
     * Creates a new compression encoder with the specified codec.
     *
     * @param codec the compression codec to use for encoding
     */
    public CompressionEncoder(CompressionCodec codec) {
        if (codec == null) {
            throw new IllegalArgumentException("Compression codec cannot be null");
        }
        this.codec = codec;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        // Read the input data
        byte[] input = new byte[msg.readableBytes()];
        msg.readBytes(input);

        // Compress the data using the codec
        byte[] compressed = codec.compress(input);

        // Write framed compressed data: [compressedLength][originalLength][compressed data]
        out.writeInt(compressed.length);
        out.writeInt(input.length);
        out.writeBytes(compressed);
    }

    /**
     * Gets the compression codec used by this encoder.
     *
     * @return the compression codec
     */
    public CompressionCodec getCodec() {
        return codec;
    }
}
