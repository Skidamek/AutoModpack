package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import pl.skidam.mcholepunch.HolepunchConnection;
import pl.skidam.mcholepunch.HolepunchFailure;

class HolepunchSocketTest {
	@Test
	void timesOutReadsUsingSocketTimeout() throws Exception {
		try (HolepunchSocket socket = new HolepunchSocket(new StubConnection())) {
			socket.setSoTimeout(10);
			assertThrows(SocketTimeoutException.class, socket.getInputStream()::read);
		}
	}

	@Test
	void drainsQueuedBytesBeforeRemoteEof() throws Exception {
		HolepunchSocket socket = new HolepunchSocket(new StubConnection());
		socket.handler().onRead(ByteBuffer.wrap(new byte[]{1, 2, 3}));
		socket.handler().onClosed(new HolepunchFailure(HolepunchFailure.Kind.CLOSED, "done", null));

		assertArrayEquals(new byte[]{1, 2, 3}, socket.getInputStream().readNBytes(3));
		assertEquals(-1, socket.getInputStream().read());
		assertTrue(socket.isClosed());
	}

	@Test
	void closeWakesBlockedRead() throws Exception {
		HolepunchSocket socket = new HolepunchSocket(new StubConnection());
		InputStream input = socket.getInputStream();
		CompletableFuture<Integer> read = CompletableFuture.supplyAsync(() -> {
			try {
				return input.read();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});

		assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
			socket.close();
			assertEquals(-1, read.join());
		});
		assertFalse(socket.isConnected());
	}

	@Test
	void pausesReadsWhenInboundQueueHitsTripwire() throws Exception {
		StubConnection connection = new StubConnection();
		try (HolepunchSocket socket = new HolepunchSocket(connection)) {
			socket.feedPlainReadData(new byte[HolepunchSocket.MAX_QUEUED_READ_BYTES]);
			assertEquals(1, connection.pauses.get());
			assertEquals(0, connection.resumes.get());
			assertEquals(HolepunchSocket.MAX_QUEUED_READ_BYTES, socket.getInputStream().readNBytes(HolepunchSocket.MAX_QUEUED_READ_BYTES).length);
			assertEquals(1, connection.resumes.get());
		}
	}

	@Test
	void camouflagedReadsSurviveDispatchesThatCompletePendingRecords() throws Exception {
		byte[] secret = new byte[16];
		TlsRecordCamouflage.Pair serverSide = TlsRecordCamouflage.create(secret, false);
		byte[] first = tlsRecord(16000);
		byte[] second = tlsRecord(16000);
		byte[] third = tlsRecord(16000);
		byte[] fourth = tlsRecord(500);
		byte[] wire = concat(camouflage(serverSide.outbound(), first), camouflage(serverSide.outbound(), second), camouflage(serverSide.outbound(), third),
				camouflage(serverSide.outbound(), fourth));
		byte[] expected = concat(first, second, third, fourth);

		try (HolepunchSocket socket = new HolepunchSocket(new StubConnection())) {
			socket.enableTlsTrafficCamouflage(true);
			// The transport coalesces wire bytes arbitrarily: cut inside the last frame so the
			// second dispatch completes a pending record and decodes far more than its own size.
			int cut = wire.length - 3;
			socket.handler().onRawRead(ByteBuffer.wrap(wire, 0, cut));
			socket.handler().onRawRead(ByteBuffer.wrap(wire, cut, wire.length - cut));

			assertArrayEquals(expected, socket.getInputStream().readNBytes(expected.length));
		}
	}

	@Test
	void camouflagedMalformedFrameClosesTheSocket() throws Exception {
		try (HolepunchSocket socket = new HolepunchSocket(new StubConnection())) {
			socket.enableTlsTrafficCamouflage(true);
			// Three VarInt continuation bytes never terminate: the frame header overflows.
			byte[] malformed = {(byte) 0x80, (byte) 0x80, (byte) 0x80, 1, 2, 3, 4};

			assertThrows(IllegalStateException.class, () -> socket.handler().onRawRead(ByteBuffer.wrap(malformed)));
			assertTrue(socket.isClosed());
		}
	}

	/** A plaintext TLS 1.3 application-data record with a patterned payload. */
	private static byte[] tlsRecord(int payloadLength) {
		byte[] record = new byte[5 + payloadLength];
		record[0] = 23;
		record[1] = 0x03;
		record[2] = 0x03;
		record[3] = (byte) (payloadLength >>> 8);
		record[4] = (byte) payloadLength;
		for (int index = 5; index < record.length; index++) record[index] = (byte) index;
		return record;
	}

	private static byte[] camouflage(TlsRecordCamouflage camouflage, byte[] record) throws Exception {
		ByteBuffer output = ByteBuffer.allocate(record.length + TlsRecordCamouflage.FRAME_HEADER_LENGTH);
		camouflage.encode(ByteBuffer.wrap(record), output);
		output.flip();
		byte[] frame = new byte[output.remaining()];
		output.get(frame);
		return frame;
	}

	private static byte[] concat(byte[]... arrays) {
		int total = 0;
		for (byte[] array : arrays) total += array.length;
		byte[] result = new byte[total];
		int offset = 0;
		for (byte[] array : arrays) {
			System.arraycopy(array, 0, result, offset, array.length);
			offset += array.length;
		}
		return result;
	}

	private static final class StubConnection implements HolepunchConnection {
		private final AtomicInteger pauses = new AtomicInteger();
		private final AtomicInteger resumes = new AtomicInteger();

		@Override
		public CompletionStage<Void> write(ByteBuffer data) {
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
		public CompletionStage<Void> commitTransportUpgrade() {
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void pauseReads() {
			pauses.incrementAndGet();
		}

		@Override
		public void resumeReads() {
			resumes.incrementAndGet();
		}

		@Override
		public void close() {}
	}
}
