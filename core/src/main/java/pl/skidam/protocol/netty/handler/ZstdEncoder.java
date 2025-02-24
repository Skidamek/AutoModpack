package pl.skidam.protocol.netty.handler;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class ZstdEncoder extends MessageToByteEncoder<ByteBuf> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        byte[] input = new byte[msg.readableBytes()];
        msg.readBytes(input);

//        var time = System.currentTimeMillis();
        byte[] compressed = Zstd.compress(input);
//        LOGGER.info("Compression time: {}ms. Saved {} bytes", System.currentTimeMillis() - time, input.length - compressed.length);

        out.writeInt(compressed.length);
        out.writeInt(input.length);
        out.writeBytes(compressed);
    }
}
