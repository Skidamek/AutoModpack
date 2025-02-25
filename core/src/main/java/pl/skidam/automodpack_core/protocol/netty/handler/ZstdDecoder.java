package pl.skidam.automodpack_core.protocol.netty.handler;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class ZstdDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 8) {
            return;
        }

        int length = in.readInt();
        int originalLength = in.readInt();

        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }

        byte[] compressed = new byte[length];
        in.readBytes(compressed);

//        var time = System.currentTimeMillis();
        byte[] decompressed = Zstd.decompress(compressed, originalLength);
//        LOGGER.info("Decompression time: {}ms. Saved {} bytes", System.currentTimeMillis() - time, originalLength - length);

        ByteBuf decompressedBuf = ctx.alloc().buffer(decompressed.length);
        decompressedBuf.writeBytes(decompressed);
        out.add(decompressedBuf);
    }
}
