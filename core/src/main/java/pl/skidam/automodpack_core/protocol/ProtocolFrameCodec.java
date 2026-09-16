package pl.skidam.automodpack_core.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;

/** Encodes and decodes the length-delimited compressed frames used by file transfer. */
public final class ProtocolFrameCodec {
	public static final int HEADER_BYTES = Integer.BYTES * 2;

	private ProtocolFrameCodec() {}

	public static void write(DataOutputStream output, CompressionCodec codec, byte[] payload, int chunkSize) throws IOException {
		if (chunkSize <= 0) throw new IllegalArgumentException("Chunk size must be positive");
		for (int offset = 0; offset < payload.length; offset += Math.min(payload.length - offset, chunkSize)) {
			int length = Math.min(payload.length - offset, chunkSize);
			byte[] chunk = new byte[length];
			System.arraycopy(payload, offset, chunk, 0, length);
			byte[] compressed = codec.compress(chunk);
			writeHeader(output, compressed.length, chunk.length);
			output.write(compressed);
		}
		output.flush();
	}

	public static void write(ByteBuf output, CompressionCodec codec, byte[] payload, int chunkSize) throws IOException {
		if (chunkSize <= 0) throw new IllegalArgumentException("Chunk size must be positive");
		for (int offset = 0; offset < payload.length; offset += Math.min(payload.length - offset, chunkSize)) {
			int length = Math.min(payload.length - offset, chunkSize);
			byte[] chunk = new byte[length];
			System.arraycopy(payload, offset, chunk, 0, length);
			byte[] compressed = codec.compress(chunk);
			output.writeInt(compressed.length);
			output.writeInt(length);
			output.writeBytes(compressed);
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

	/** Returns null when the input does not contain one complete frame yet. */
	public static ByteBuf read(ByteBuf input, ByteBufAllocator allocator, CompressionCodec codec, int chunkSize) throws IOException {
		if (input.readableBytes() < HEADER_BYTES) return null;

		input.markReaderIndex();
		int compressedLength = input.readInt();
		int originalLength = input.readInt();
		validateLengths(codec, chunkSize, compressedLength, originalLength);
		if (input.readableBytes() < compressedLength) {
			input.resetReaderIndex();
			return null;
		}

		byte[] compressed = new byte[compressedLength];
		input.readBytes(compressed);
		byte[] decompressed = decompress(codec, compressed, 0, compressedLength, originalLength);
		ByteBuf output = allocator.buffer(originalLength, originalLength);
		output.writeBytes(decompressed);
		return output;
	}

	private static void writeHeader(DataOutputStream output, int compressedLength, int originalLength) throws IOException {
		output.writeInt(compressedLength);
		output.writeInt(originalLength);
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

	private static byte[] decompress(CompressionCodec codec, byte[] compressed, int offset, int length, int originalLength) throws IOException {
		byte[] decompressed = codec.decompress(compressed, offset, length, originalLength);
		if (decompressed.length != originalLength)
			throw new IOException("Codec returned " + decompressed.length + " bytes for a frame that declares " + originalLength);
		return decompressed;
	}
}
