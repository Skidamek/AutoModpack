package pl.skidam.automodpack_core.protocol.netty.handler;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class ZstdEncoder extends MessageToByteEncoder<ByteBuf> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        byte[] input = new byte[msg.readableBytes()];
        msg.readBytes(input);

        byte[] compressed = Zstd.compress(input);

        out.writeInt(input.length);
        out.writeBytes(compressed);
    }
}
