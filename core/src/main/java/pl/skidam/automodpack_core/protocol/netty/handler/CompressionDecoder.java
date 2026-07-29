package pl.skidam.automodpack_core.protocol.netty.handler;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

public class CompressionDecoder extends ByteToMessageDecoder {
	private CompressionCodec codec;
	private CompressionType compressionType;

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		if (in.readableBytes() < Integer.BYTES * 2) return;

		in.markReaderIndex();
		int compressedLength = in.readInt();
		int originalLength = in.readInt();
		int chunkSize = ctx.channel().attr(NettyServer.CHUNK_SIZE).get();
		CompressionCodec codec = codec(ctx);

		if (originalLength < 0 || originalLength > chunkSize) {
			throw new IllegalArgumentException("Frame original length (" + originalLength + ") exceeds chunk size (" + chunkSize + ")");
		}

		int maxCompressedLength = codec.maxCompressedLength(originalLength);
		if (compressedLength < 0 || compressedLength > maxCompressedLength) {
			throw new IllegalArgumentException("Frame compressed length (" + compressedLength + ") exceeds codec limit (" + maxCompressedLength + ")");
		}

		if (in.readableBytes() < compressedLength) {
			in.resetReaderIndex();
			return;
		}

		byte[] compressed = new byte[compressedLength];
		in.readBytes(compressed);
		byte[] decompressed = codec.decompress(compressed, originalLength);
		ByteBuf decompressedBuf = ctx.alloc().buffer(originalLength);
		decompressedBuf.writeBytes(decompressed);
		out.add(decompressedBuf);
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
