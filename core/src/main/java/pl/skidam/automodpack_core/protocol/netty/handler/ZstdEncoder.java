package pl.skidam.automodpack_core.protocol.netty.handler;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

public class ZstdEncoder extends MessageToByteEncoder<ByteBuf> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        if (!ctx.pipeline().channel().attr(NettyServer.USE_COMPRESSION).get()) {
            out.writeInt(msg.readableBytes());
            out.writeBytes(msg);
            return;
        }

        byte[] input = new byte[msg.readableBytes()];
        msg.readBytes(input);

        byte[] compressed = Zstd.compress(input);

        out.writeInt(compressed.length);
        out.writeInt(input.length);
        out.writeBytes(compressed);
    }
}
