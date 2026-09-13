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
	private final ProtocolFrameCodec.FrameScratch scratch = new ProtocolFrameCodec.FrameScratch();

	/**
	 * Pre-sizes the frame the same way the worker does in {@code ServerMessageHandler.writeFrame}, so a large
	 * payload never grows the buffer by doubling. Exact for single-frame payloads; a multi-chunk message only
	 * grows once past it.
	 */
	@Override
	protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, ByteBuf msg, boolean preferDirect) {
		return ctx.alloc().ioBuffer(ProtocolFrameCodec.HEADER_BYTES + codec(ctx).maxCompressedLength(msg.readableBytes()));
	}

	@Override
	protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
		Integer chunkSize = ctx.channel().attr(NettyServer.CHUNK_SIZE).get();
		if (chunkSize == null) throw new IllegalStateException("Chunk size has not been configured");
		ProtocolFrameCodec.write(out, codec(ctx), msg, chunkSize, scratch);
	}

	/** The negotiated codec is a cheap wrapper, so it is simply created per message instead of memoized per handler. */
	private static CompressionCodec codec(ChannelHandlerContext ctx) {
		CompressionType selected = ctx.channel().attr(NettyServer.COMPRESSION_TYPE).get();
		if (selected == null) throw new IllegalStateException("Compression type has not been configured");
		return CompressionFactory.createCodec(selected);
	}
}
