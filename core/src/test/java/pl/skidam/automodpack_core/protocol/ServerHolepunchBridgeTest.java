package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedWriteHandler;

class ServerHolepunchBridgeTest {
	@Test
	void pumpDrainsChunkedWritesAndTrailingControlFrame() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel(new ChunkedWriteHandler());
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream output = new DataOutputStream(bytes);

		channel.write(new ChunkedStream(new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5}), 2));
		channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{6, 7}));
		ServerHolepunchBridge.pumpEmbeddedChannel(channel, output);

		assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7}, bytes.toByteArray());
		channel.finishAndReleaseAll();
	}
}
