package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
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
		// java.net.Socket semantics: connectedness survives close.
		assertTrue(socket.isConnected());
	}

	@Test
	void pausesReadsWhenInboundQueueHitsTripwire() throws Exception {
		StubConnection connection = new StubConnection();
		try (HolepunchSocket socket = new HolepunchSocket(connection)) {
			socket.handler().onRead(ByteBuffer.wrap(new byte[HolepunchSocket.MAX_QUEUED_READ_BYTES]));
			assertEquals(1, connection.pauses.get());
			assertEquals(0, connection.resumes.get());
			assertEquals(HolepunchSocket.MAX_QUEUED_READ_BYTES, socket.getInputStream().readNBytes(HolepunchSocket.MAX_QUEUED_READ_BYTES).length);
			assertEquals(1, connection.resumes.get());
		}
	}

	@Test
	void camouflagedReadsSurviveArbitraryChunkingAndHeaderSplits() throws Exception {
		byte[] secret = new byte[16];
		TlsRecordCamouflage.Pair serverSide = TlsRecordCamouflage.create(secret, false);
		byte[] first = tlsRecord(16000);
		byte[] second = tlsRecord(16000);
		byte[] third = tlsRecord(16000);
		byte[] fourth = tlsRecord(500);
		byte[] wire = concat(camouflage(serverSide.outbound(), first), camouflage(serverSide.outbound(), second), camouflage(serverSide.outbound(), third),
				camouflage(serverSide.outbound(), fourth));
		byte[] expected = concat(first, second, third, fourth);
		assertEquals(expected.length, wire.length, "header-masked stream must add zero framing bytes");
		assertFalse(Arrays.equals(Arrays.copyOfRange(wire, 0, 5), Arrays.copyOfRange(wire, first.length, first.length + 5)),
				"identical TLS headers must mask differently across consecutive records");

		try (HolepunchSocket socket = new HolepunchSocket(new StubConnection())) {
			socket.enableTlsTrafficCamouflage(true);
			// Cut mid-header on the first record and mid-payload on the last record.
			int headerCut = 2;
			int tailCut = wire.length - 3;
			socket.handler().onRawRead(ByteBuffer.wrap(wire, 0, headerCut));
			socket.handler().onRawRead(ByteBuffer.wrap(wire, headerCut, tailCut - headerCut));
			socket.handler().onRawRead(ByteBuffer.wrap(wire, tailCut, wire.length - tailCut));

			assertArrayEquals(expected, socket.getInputStream().readNBytes(expected.length));
		}
	}

	@Test
	void camouflagedMalformedHeaderClosesTheSocket() throws Exception {
		byte[] secret = new byte[16];
		// Encrypt an invalid TLS header (contentType=0x00) using the wrong key so unmasked header fails validation.
		byte[] wrongSecret = new byte[16];
		wrongSecret[0] = 1;
		TlsRecordCamouflage.Pair wrongSender = TlsRecordCamouflage.create(wrongSecret, false);
		byte[] malformed = camouflage(wrongSender.outbound(), tlsRecord(64));

		try (HolepunchSocket socket = new HolepunchSocket(new StubConnection())) {
			socket.enableTlsTrafficCamouflage(true);

			assertThrows(IllegalStateException.class, () -> socket.handler().onRawRead(ByteBuffer.wrap(malformed)));
			assertTrue(socket.isClosed());
		}
	}

	@Test
	void copiesCallbackScopedReadBuffersBeforeReturning() throws Exception {
		try (HolepunchSocket socket = new HolepunchSocket(new StubConnection())) {
			byte[] backing = {1, 2, 3, 4};
			socket.handler().onRead(ByteBuffer.wrap(backing).asReadOnlyBuffer());
			backing[0] = 99;
			backing[1] = 98;

			byte[] rawBacking = {5, 6, 7, 8};
			socket.handler().onRawRead(ByteBuffer.wrap(rawBacking).asReadOnlyBuffer());
			rawBacking[0] = 88;
			rawBacking[1] = 87;

			assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, socket.getInputStream().readNBytes(8));
		}
	}

	@Test
	void onOpenBindsConnectionAndClosesLateConnectionIfSocketAlreadyClosed() {
		StubConnection live = new StubConnection();
		try (HolepunchSocket socket = new HolepunchSocket()) {
			socket.handler().onOpen(live);
			assertTrue(socket.isConnected());
			assertEquals(0, live.closes.get());
		}
		assertEquals(1, live.closes.get());

		StubConnection late = new StubConnection();
		HolepunchSocket closedSocket = new HolepunchSocket();
		closedSocket.close();
		closedSocket.handler().onOpen(late);
		assertEquals(1, late.closes.get());
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
		ByteBuffer output = ByteBuffer.allocate(record.length);
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
		private final AtomicInteger closes = new AtomicInteger();

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
		public void close() {
			closes.incrementAndGet();
		}
	}
}
