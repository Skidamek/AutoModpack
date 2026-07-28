package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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

	private static final class StubConnection implements HolepunchConnection {
		@Override
		public CompletionStage<Void> write(ByteBuffer data) {
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void close() {}
	}
}
