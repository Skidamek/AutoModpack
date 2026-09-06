package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import pl.skidam.mcholepunch.HolepunchConnection;

class ServerHolepunchBridgeTest {

	@Test
	void camouflageHandlersPassThroughBeforeTheHandoffAndRoundTripAfterIt() throws Exception {
		FakeConnection connection = new FakeConnection();
		EmbeddedChannel channel = new EmbeddedChannel();
		channel.pipeline().addLast("holepunch-camouflage-encoder", new ServerHolepunchBridge.CamouflageEncoder(connection));
		channel.pipeline().addLast("holepunch-camouflage-decoder", new ServerHolepunchBridge.CamouflageDecoder(connection));
		byte[] record = tlsRecord(64);
		TlsRecordCamouflage.Pair client = TlsRecordCamouflage.create(connection.transportSecret(), true);

		channel.writeOutbound(Unpooled.wrappedBuffer(record));
		assertArrayEquals(record, readBytes(channel.readOutbound()));

		connection.activateRaw();
		channel.writeOutbound(Unpooled.wrappedBuffer(record));
		ByteBuffer framed = ByteBuffer.wrap(readBytes(channel.readOutbound()));
		ByteBuffer decoded = ByteBuffer.allocate(record.length + TlsRecordCamouflage.FRAME_HEADER_LENGTH);
		client.inbound().decode(framed, decoded);
		decoded.flip();
		byte[] roundTripped = new byte[decoded.remaining()];
		decoded.get(roundTripped);
		assertArrayEquals(record, roundTripped);

		ByteBuffer encodedByClient = ByteBuffer.allocate(record.length + TlsRecordCamouflage.FRAME_HEADER_LENGTH);
		client.outbound().encode(ByteBuffer.wrap(record), encodedByClient);
		encodedByClient.flip();
		byte[] camouflaged = new byte[encodedByClient.remaining()];
		encodedByClient.get(camouflaged);
		channel.writeInbound(Unpooled.wrappedBuffer(camouflaged, 0, 3));
		channel.writeInbound(Unpooled.wrappedBuffer(camouflaged, 3, camouflaged.length - 3));
		assertArrayEquals(record, readBytes(channel.readInbound()));

		channel.finishAndReleaseAll();
	}

	private static byte[] tlsRecord(int payloadLength) {
		byte[] record = new byte[5 + payloadLength];
		record[0] = 0x17;
		record[1] = 0x03;
		record[2] = 0x03;
		record[3] = (byte) (payloadLength >>> 8);
		record[4] = (byte) payloadLength;
		byte[] payload = new byte[payloadLength];
		ThreadLocalRandom.current().nextBytes(payload);
		System.arraycopy(payload, 0, record, 5, payloadLength);
		return record;
	}

	private static byte[] readBytes(ByteBuf buffer) {
		try {
			byte[] bytes = new byte[buffer.readableBytes()];
			buffer.readBytes(bytes);
			return bytes;
		} finally {
			buffer.release();
		}
	}

	private static final class FakeConnection implements HolepunchConnection {
		private final byte[] secret = new byte[16];
		private boolean raw;

		FakeConnection() {
			ThreadLocalRandom.current().nextBytes(secret);
		}

		void activateRaw() {
			raw = true;
		}

		@Override
		public CompletionStage<Void> write(ByteBuffer data) {
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public boolean isRaw() {
			return raw;
		}

		@Override
		public byte[] transportSecret() {
			return secret.clone();
		}

		@Override
		public CompletionStage<Void> commitTransportUpgrade() {
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void pauseReads() {}

		@Override
		public void resumeReads() {}

		@Override
		public void close() {}
	}
}
