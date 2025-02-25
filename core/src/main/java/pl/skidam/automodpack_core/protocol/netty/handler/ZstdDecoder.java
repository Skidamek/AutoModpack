package pl.skidam.automodpack_core.protocol.netty.handler;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class ZstdDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int originalLength = in.readInt();

        byte[] compressed = new byte[in.readableBytes()];
        in.readBytes(compressed);

        byte[] decompressed = Zstd.decompress(compressed, originalLength);

        ByteBuf decompressedBuf = ctx.alloc().buffer(decompressed.length);
        decompressedBuf.writeBytes(decompressed);
        out.add(decompressedBuf);
    }
}
