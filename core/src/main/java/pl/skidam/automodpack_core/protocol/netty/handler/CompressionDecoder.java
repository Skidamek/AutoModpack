package pl.skidam.automodpack_core.protocol.netty.handler;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import pl.skidam.automodpack_core.protocol.ProtocolFrameCodec;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

public class CompressionDecoder extends ByteToMessageDecoder {
	private final ProtocolFrameCodec.FrameScratch scratch = new ProtocolFrameCodec.FrameScratch();

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		Integer chunkSize = ctx.channel().attr(NettyServer.CHUNK_SIZE).get();
		if (chunkSize == null) throw new IllegalStateException("Chunk size has not been configured");
		ByteBuf frame = ProtocolFrameCodec.read(in, ctx.alloc(), NettyServer.compressionCodec(ctx.channel()), chunkSize, scratch);
		if (frame != null) out.add(frame);
	}
}
