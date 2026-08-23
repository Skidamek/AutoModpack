package pl.skidam.automodpack_core.protocol.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import pl.skidam.automodpack_core.protocol.ProtocolFrameCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

public class CompressionEncoder extends MessageToByteEncoder<ByteBuf> {
	private CompressionCodec codec;
	private CompressionType compressionType;
	private final ProtocolFrameCodec.FrameScratch scratch = new ProtocolFrameCodec.FrameScratch();

	@Override
	protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
		Integer chunkSize = ctx.channel().attr(NettyServer.CHUNK_SIZE).get();
		if (chunkSize == null) throw new IllegalStateException("Chunk size has not been configured");
		ProtocolFrameCodec.write(out, codec(ctx), msg, chunkSize, scratch);
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
}
