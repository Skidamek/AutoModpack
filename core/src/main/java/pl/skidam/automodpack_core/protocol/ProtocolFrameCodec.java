package pl.skidam.automodpack_core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;

/** Encodes and decodes the length-delimited compressed frames used by file transfer. */
public final class ProtocolFrameCodec {
	public static final int HEADER_BYTES = Integer.BYTES * 2;

	private ProtocolFrameCodec() {}

	public static void write(DataOutputStream output, CompressionCodec codec, byte[] payload, int chunkSize) throws IOException {
		if (chunkSize <= 0) throw new IllegalArgumentException("Chunk size must be positive");
		FrameScratch scratch = new FrameScratch();
		for (int offset = 0; offset < payload.length; offset += Math.min(payload.length - offset, chunkSize)) {
			int length = Math.min(payload.length - offset, chunkSize);
			int compressedLength = compress(codec, payload, offset, length, scratch);
			writeHeader(output, compressedLength, length);
			output.write(scratch.compressed, 0, compressedLength);
		}
		output.flush();
	}

	public static void write(ByteBuf output, CompressionCodec codec, byte[] payload, int chunkSize) throws IOException {
		write(output, codec, payload, chunkSize, new FrameScratch());
	}

	public static void write(ByteBuf output, CompressionCodec codec, byte[] payload, int chunkSize, FrameScratch scratch) throws IOException {
		if (chunkSize <= 0) throw new IllegalArgumentException("Chunk size must be positive");
		for (int offset = 0; offset < payload.length; offset += Math.min(payload.length - offset, chunkSize)) {
			int length = Math.min(payload.length - offset, chunkSize);
			int compressedLength = compress(codec, payload, offset, length, scratch);
			output.writeInt(compressedLength);
			output.writeInt(length);
			output.writeBytes(scratch.compressed, 0, compressedLength);
		}
	}

	public static void write(ByteBuf output, CompressionCodec codec, ByteBuf payload, int chunkSize, FrameScratch scratch) throws IOException {
		if (chunkSize <= 0) throw new IllegalArgumentException("Chunk size must be positive");
		int offset = payload.readerIndex();
		int end = payload.writerIndex();
		while (offset < end) {
			int length = Math.min(end - offset, chunkSize);
			int compressedLength;
			if (payload.hasArray()) {
				compressedLength = compress(codec, payload.array(), payload.arrayOffset() + offset, length, scratch);
			} else {
				compressedLength = compress(codec, payload.nioBuffer(offset, length), scratch);
			}
			output.writeInt(compressedLength);
			output.writeInt(length);
			output.writeBytes(scratch.compressed, 0, compressedLength);
			offset += length;
		}
	}

	public static byte[] read(DataInputStream input, CompressionCodec codec, int chunkSize, byte[] compressedBuffer) throws IOException {
		int compressedLength = input.readInt();
		int originalLength = input.readInt();
		validateLengths(codec, chunkSize, compressedLength, originalLength);
		if (compressedLength > compressedBuffer.length) throw new IOException("Compressed frame exceeds the network buffer capacity");
		input.readFully(compressedBuffer, 0, compressedLength);
		return decompress(codec, compressedBuffer, 0, compressedLength, originalLength);
	}

	public static Frame read(DataInputStream input, CompressionCodec codec, int chunkSize, FrameScratch scratch) throws IOException {
		int compressedLength = input.readInt();
		int originalLength = input.readInt();
		validateLengths(codec, chunkSize, compressedLength, originalLength);
		scratch.ensureCompressed(compressedLength);
		input.readFully(scratch.compressed, 0, compressedLength);
		scratch.ensureDecompressed(originalLength);
		decompress(codec, scratch.compressed, 0, compressedLength, originalLength, scratch.decompressed);
		return new Frame(scratch.decompressed, originalLength);
	}

	/** Returns null when the input does not contain one complete frame yet. */
	public static ByteBuf read(ByteBuf input, ByteBufAllocator allocator, CompressionCodec codec, int chunkSize) throws IOException {
		return read(input, allocator, codec, chunkSize, new FrameScratch());
	}

	public static ByteBuf read(ByteBuf input, ByteBufAllocator allocator, CompressionCodec codec, int chunkSize, FrameScratch scratch) throws IOException {
		if (input.readableBytes() < HEADER_BYTES) return null;

		input.markReaderIndex();
		int compressedLength = input.readInt();
		int originalLength = input.readInt();
		validateLengths(codec, chunkSize, compressedLength, originalLength);
		if (input.readableBytes() < compressedLength) {
			input.resetReaderIndex();
			return null;
		}

		scratch.ensureCompressed(compressedLength);
		input.readBytes(scratch.compressed, 0, compressedLength);
		scratch.ensureDecompressed(originalLength);
		decompress(codec, scratch.compressed, 0, compressedLength, originalLength, scratch.decompressed);
		ByteBuf output = allocator.buffer(originalLength, originalLength);
		output.writeBytes(scratch.decompressed, 0, originalLength);
		return output;
	}

	public static final class FrameScratch {
		private byte[] compressed = new byte[0];
		private byte[] decompressed = new byte[0];
		private ByteBuffer compressedOutput = ByteBuffer.allocate(0);

		private void ensureCompressed(int length) {
			if (compressed.length < length) {
				compressed = new byte[length];
				compressedOutput = ByteBuffer.wrap(compressed);
			}
		}

		private void ensureDecompressed(int length) {
			if (decompressed.length < length) decompressed = new byte[length];
		}
	}

	public record Frame(byte[] data, int length) {}

	private static void writeHeader(DataOutputStream output, int compressedLength, int originalLength) throws IOException {
		output.writeInt(compressedLength);
		output.writeInt(originalLength);
	}

	private static int compress(CompressionCodec codec, byte[] input, int offset, int length, FrameScratch scratch) throws IOException {
		scratch.ensureCompressed(codec.maxCompressedLength(length));
		return codec.compress(input, offset, length, scratch.compressed, 0);
	}

	private static int compress(CompressionCodec codec, ByteBuffer input, FrameScratch scratch) throws IOException {
		int maxCompressedLength = codec.maxCompressedLength(input.remaining());
		scratch.ensureCompressed(maxCompressedLength);
		scratch.compressedOutput.clear().limit(maxCompressedLength);
		return codec.compress(input, scratch.compressedOutput);
	}

	private static void validateLengths(CompressionCodec codec, int chunkSize, int compressedLength, int originalLength) throws IOException {
		if (chunkSize <= 0) throw new IOException("Chunk size is not configured");
		if (originalLength < 0 || originalLength > chunkSize)
			throw new IOException("Frame original length (" + originalLength + ") exceeds chunk size (" + chunkSize + ")");

		int maxCompressedLength;
		try {
			maxCompressedLength = codec.maxCompressedLength(originalLength);
		} catch (RuntimeException e) {
			throw new IOException("Could not calculate the maximum compressed frame length", e);
		}
		if (compressedLength < 0 || compressedLength > maxCompressedLength)
			throw new IOException("Frame compressed length (" + compressedLength + ") exceeds codec limit (" + maxCompressedLength + ")");
	}

	private static void decompress(CompressionCodec codec, byte[] compressed, int offset, int length, int originalLength, byte[] output) throws IOException {
		int decompressedLength = codec.decompress(compressed, offset, length, originalLength, output, 0);
		if (decompressedLength != originalLength) throw new IOException("Codec returned " + decompressedLength + " bytes for a frame that declares " + originalLength);
	}

	private static byte[] decompress(CompressionCodec codec, byte[] compressed, int offset, int length, int originalLength) throws IOException {
		byte[] output = new byte[originalLength];
		decompress(codec, compressed, offset, length, originalLength, output);
		return output;
	}
}
