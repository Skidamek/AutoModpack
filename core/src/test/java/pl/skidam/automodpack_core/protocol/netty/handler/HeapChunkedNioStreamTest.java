package pl.skidam.automodpack_core.protocol.netty.handler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.channels.Channels;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;

class HeapChunkedNioStreamTest {
	@Test
	void readsAllInputIntoHeapChunks() throws Exception {
		byte[] expected = new byte[2 * 1024 + 37];
		for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i * 13);
		HeapChunkedNioStream stream = new HeapChunkedNioStream(Channels.newChannel(new ByteArrayInputStream(expected)), 1024);
		ByteArrayOutputStream actual = new ByteArrayOutputStream(expected.length);
		try {
			for (;;) {
				ByteBuf chunk = stream.readChunk(UnpooledByteBufAllocator.DEFAULT);
				if (chunk != null) {
					assertTrue(chunk.hasArray());
					byte[] bytes = new byte[chunk.readableBytes()];
					chunk.readBytes(bytes);
					actual.writeBytes(bytes);
					chunk.release();
				}
				if (stream.isEndOfInput()) break;
			}
		} finally {
			stream.close();
		}
		assertArrayEquals(expected, actual.toByteArray());
	}
}
