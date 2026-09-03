package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedWriteHandler;

import pl.skidam.automodpack_core.protocol.netty.BackpressuredEmbeddedChannel;
import pl.skidam.mcholepunch.HolepunchConnection;

class ServerHolepunchBridgeTest {
	@Test
	void pumpDrainsChunkedWritesAndTrailingControlFrame() throws Exception {
		EmbeddedChannel channel = new BackpressuredEmbeddedChannel(4);
		channel.pipeline().addLast(new ChunkedWriteHandler());
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream output = new DataOutputStream(bytes);

		channel.write(new ChunkedStream(new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5}), 2));
		channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{6, 7}));
		ServerHolepunchBridge.pumpEmbeddedChannel(channel, output);

		assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7}, bytes.toByteArray());
		channel.finishAndReleaseAll();
	}

	@Test
	void chunkedWriteDoesNotMaterializeTheWholeStreamUntilDrained() throws Exception {
		int watermark = 2048;
		int chunkSize = 256;
		byte[] payload = new byte[64 * 1024];
		for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
		BackpressuredEmbeddedChannel channel = new BackpressuredEmbeddedChannel(watermark);
		channel.pipeline().addLast(new ChunkedWriteHandler());
		channel.writeAndFlush(new ChunkedStream(new ByteArrayInputStream(payload), chunkSize));
		channel.runPendingTasks();
		channel.flushOutbound();

		long queued = 0;
		for (Object message : channel.outboundMessages()) queued += ((ByteBuf) message).readableBytes();
		assertTrue(queued > 0);
		assertTrue(queued <= watermark, "queued=" + queued);
		assertTrue(channel.outboundMessages().size() < payload.length / chunkSize);
		int queuedMessages = channel.outboundMessages().size();
		channel.flushOutbound();
		assertEquals(queuedMessages, channel.outboundMessages().size());

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ServerHolepunchBridge.pumpEmbeddedChannel(channel, new DataOutputStream(bytes));
		assertArrayEquals(payload, bytes.toByteArray());
		channel.finishAndReleaseAll();
	}

	@Test
	void pumpSendsAWholeNioOutboundBufferAsOneHolepunchWrite() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		AtomicInteger writes = new AtomicInteger();
		HolepunchConnection connection = new HolepunchConnection() {
			@Override
			public CompletableFuture<Void> write(ByteBuffer data) {
				ByteBuffer copy = data.duplicate();
				byte[] chunk = new byte[copy.remaining()];
				copy.get(chunk);
				bytes.writeBytes(chunk);
				writes.incrementAndGet();
				return CompletableFuture.completedFuture(null);
			}

			@Override
			public boolean isRaw() {
				return false;
			}

			@Override
			public byte[] transportSecret() {
				return new byte[16];
			}

			@Override
			public CompletableFuture<Void> commitTransportUpgrade() {
				return CompletableFuture.completedFuture(null);
			}

			@Override
			public void pauseReads() {}

			@Override
			public void resumeReads() {}

			@Override
			public void close() {}
		};
		HolepunchSocket socket = new HolepunchSocket(connection);
		byte[] payload = new byte[20 * 1024];
		for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
		channel.writeAndFlush(Unpooled.wrappedBuffer(payload));
		ServerHolepunchBridge.pumpEmbeddedChannel(channel, socket);

		assertEquals(1, writes.get());
		assertArrayEquals(payload, bytes.toByteArray());
		socket.close();
		channel.finishAndReleaseAll();
	}
}
