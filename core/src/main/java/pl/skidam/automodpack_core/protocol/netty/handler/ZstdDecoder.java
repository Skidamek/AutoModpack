package pl.skidam.automodpack_core.protocol.netty.handler;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

import java.util.List;

public class ZstdDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!ctx.pipeline().channel().attr(NettyServer.USE_COMPRESSION).get()) {
            if (in.readableBytes() < 4) {
                return;
            }

            int length = in.readInt();

            if (in.readableBytes() < length) {
                in.resetReaderIndex();
                return;
            }

            ByteBuf buf = in.readBytes(length);
            out.add(buf);
            return;
        }


        if (in.readableBytes() < 8) {
            return;
        }

        int compressedLength = in.readInt();
        int originalLength = in.readInt();

        if (in.readableBytes() < compressedLength) {
            in.resetReaderIndex();
            return;
        }

        byte[] compressed = new byte[compressedLength];
        in.readBytes(compressed);

        byte[] decompressed = Zstd.decompress(compressed, originalLength);

        if (decompressed.length != originalLength) {
            throw new IllegalStateException("Decompressed length does not match original length");
        }

        ByteBuf decompressedBuf = ctx.alloc().buffer(originalLength);
        decompressedBuf.writeBytes(decompressed);
        out.add(decompressedBuf);
    }
}
