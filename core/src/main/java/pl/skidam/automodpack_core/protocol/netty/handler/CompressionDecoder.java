package pl.skidam.automodpack_core.protocol.netty.handler;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import pl.skidam.automodpack_core.protocol.ProtocolFrameCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

public class CompressionDecoder extends ByteToMessageDecoder {
	private CompressionCodec codec;
	private CompressionType compressionType;

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		Integer chunkSize = ctx.channel().attr(NettyServer.CHUNK_SIZE).get();
		if (chunkSize == null) throw new IllegalStateException("Chunk size has not been configured");
		ByteBuf frame = ProtocolFrameCodec.read(in, ctx.alloc(), codec(ctx), chunkSize);
		if (frame != null) out.add(frame);
	}

	private CompressionCodec codec(ChannelHandlerContext ctx) {
		CompressionType selected = ctx.channel().attr(NettyServer.COMPRESSION_TYPE).get();
		if (selected == null) throw new IllegalStateException("Compression type has not been configured");
		if (codec == null || compressionType != selected) {
			codec = CompressionFactory.createCodec(selected);
			compressionType = selected;
		}
		return codec;
	}

	public CompressionCodec getCodec() {
		return codec;
	}
}
