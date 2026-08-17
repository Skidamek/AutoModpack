package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static pl.skidam.automodpack_core.protocol.NetUtils.DEFAULT_CHUNK_SIZE;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;

class ProtocolFrameCodecTest {
	@Test
	void streamFramesRoundTripAcrossChunks() throws Exception {
		CompressionCodec codec = CompressionFactory.createCodec(CompressionType.GZIP);
		byte[] payload = new byte[DEFAULT_CHUNK_SIZE + 17];
		for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);

		ByteArrayOutputStream encoded = new ByteArrayOutputStream();
		ProtocolFrameCodec.write(new DataOutputStream(encoded), codec, payload, DEFAULT_CHUNK_SIZE);

		ByteArrayOutputStream decoded = new ByteArrayOutputStream();
		byte[] compressedBuffer = new byte[codec.maxCompressedLength(DEFAULT_CHUNK_SIZE)];
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded.toByteArray()))) {
			while (input.available() > 0) decoded.write(ProtocolFrameCodec.read(input, codec, DEFAULT_CHUNK_SIZE, compressedBuffer));
		}

		assertArrayEquals(payload, decoded.toByteArray());
	}

	@Test
	void nettyDecoderWaitsForTheCompleteCompressedFrame() throws Exception {
		CompressionCodec codec = CompressionFactory.createCodec(CompressionType.GZIP);
		byte[] payload = "partial frames must be accumulated before decompression".getBytes(StandardCharsets.UTF_8);
		ByteBuf encoded = Unpooled.buffer();
		ByteBuf inbound = Unpooled.buffer();
		try {
			ProtocolFrameCodec.write(encoded, codec, payload, DEFAULT_CHUNK_SIZE);
			int split = ProtocolFrameCodec.HEADER_BYTES + 1;
			inbound.writeBytes(encoded, 0, split);
			assertNull(ProtocolFrameCodec.read(inbound, UnpooledByteBufAllocator.DEFAULT, codec, DEFAULT_CHUNK_SIZE));
			inbound.writeBytes(encoded, split, encoded.readableBytes() - split);
			ByteBuf decoded = ProtocolFrameCodec.read(inbound, UnpooledByteBufAllocator.DEFAULT, codec, DEFAULT_CHUNK_SIZE);
			try {
				byte[] actual = new byte[decoded.readableBytes()];
				decoded.readBytes(actual);
				assertArrayEquals(payload, actual);
			} finally {
				decoded.release();
			}
		} finally {
			encoded.release();
			inbound.release();
		}
	}

	@Test
	void nettyWriterSplitsPayloadsLargerThanTheNegotiatedChunkSize() throws Exception {
		CompressionCodec codec = CompressionFactory.createCodec(CompressionType.GZIP);
		byte[] payload = new byte[DEFAULT_CHUNK_SIZE + 17];
		for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 13);

		ByteBuf encoded = Unpooled.buffer();
		try {
			ProtocolFrameCodec.write(encoded, codec, payload, DEFAULT_CHUNK_SIZE);
			ByteArrayOutputStream decoded = new ByteArrayOutputStream();
			while (encoded.isReadable()) {
				ByteBuf frame = ProtocolFrameCodec.read(encoded, UnpooledByteBufAllocator.DEFAULT, codec, DEFAULT_CHUNK_SIZE);
				try {
					byte[] bytes = new byte[frame.readableBytes()];
					frame.readBytes(bytes);
					decoded.write(bytes);
				} finally {
					frame.release();
				}
			}
			assertArrayEquals(payload, decoded.toByteArray());
		} finally {
			encoded.release();
		}
	}

	@Test
	void nettyWriterAndReaderHandleDirectPayloadsWithReusableScratch() throws Exception {
		CompressionCodec codec = CompressionFactory.createCodec(CompressionType.ZSTD);
		byte[] payload = new byte[DEFAULT_CHUNK_SIZE + 37];
		for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 17);

		ByteBuf source = Unpooled.directBuffer(payload.length).writeBytes(payload);
		ByteBuf encoded = Unpooled.buffer();
		ProtocolFrameCodec.FrameScratch writeScratch = new ProtocolFrameCodec.FrameScratch();
		ProtocolFrameCodec.FrameScratch readScratch = new ProtocolFrameCodec.FrameScratch();
		try {
			ProtocolFrameCodec.write(encoded, codec, source, DEFAULT_CHUNK_SIZE, writeScratch);
			ByteArrayOutputStream decoded = new ByteArrayOutputStream(payload.length);
			while (encoded.isReadable()) {
				ByteBuf frame = ProtocolFrameCodec.read(encoded, UnpooledByteBufAllocator.DEFAULT, codec, DEFAULT_CHUNK_SIZE, readScratch);
				try {
					byte[] bytes = new byte[frame.readableBytes()];
					frame.readBytes(bytes);
					decoded.write(bytes);
				} finally {
					frame.release();
				}
			}
			assertArrayEquals(payload, decoded.toByteArray());
		} finally {
			source.release();
			encoded.release();
		}
	}
}
