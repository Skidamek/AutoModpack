package pl.skidam.automodpack_core.protocol.netty.handler;

import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.stream.ChunkedInput;

/** Reads a file into heap-backed Netty buffers so byte-array codecs can consume it without a staging copy. */
final class HeapChunkedNioStream implements ChunkedInput<ByteBuf> {
	private final ReadableByteChannel input;
	private final int chunkSize;
	private final long expectedLength;
	private long progress;
	private boolean endOfInput;

	HeapChunkedNioStream(ReadableByteChannel input, int chunkSize) {
		this(input, chunkSize, -1);
	}

	HeapChunkedNioStream(ReadableByteChannel input, int chunkSize, long expectedLength) {
		if (input == null) throw new NullPointerException("input");
		if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be positive");
		if (expectedLength < -1) throw new IllegalArgumentException("expectedLength must not be less than -1");
		this.input = input;
		this.chunkSize = chunkSize;
		this.expectedLength = expectedLength;
	}

	@Override
	public boolean isEndOfInput() {
		return endOfInput;
	}

	@Override
	public void close() throws Exception {
		input.close();
	}

	@Override
	public ByteBuf readChunk(ChannelHandlerContext context) throws Exception {
		return readChunk(context.alloc());
	}

	@Override
	public ByteBuf readChunk(ByteBufAllocator allocator) throws Exception {
		if (endOfInput) return null;
		long remaining = expectedLength < 0 ? chunkSize : expectedLength - progress;
		if (remaining <= 0) {
			endOfInput = true;
			return null;
		}
		int requested = (int) Math.min(chunkSize, remaining);
		ByteBuf chunk = allocator.heapBuffer(requested, requested);
		boolean success = false;
		try {
			ByteBuffer destination = chunk.nioBuffer(0, requested);
			int length = 0;
			while (length < requested) {
				int read = input.read(destination);
				if (read < 0) {
					endOfInput = true;
					break;
				}
				if (read == 0) continue;
				length += read;
			}
			chunk.writerIndex(length);
			progress += length;
			if (length == 0) return null;
			success = true;
			return chunk;
		} finally {
			if (!success) chunk.release();
		}
	}

	@Override
	public long length() {
		return expectedLength;
	}

	@Override
	public long progress() {
		return progress;
	}
}
