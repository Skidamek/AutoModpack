package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.protocol.DocumentFetch;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.update.ClientStorage;

/** The custom track's lifecycle: eager stream on first contact, cache forever, withdraw on 404, no cache past the cap. */
class WaitingMusicTest {
	private static final byte[] TRACK = "waiting-music-bytes".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path tempDir;

	private ClientStorage storage() throws IOException {
		return ClientStorage.open(tempDir.resolve("game"));
	}

	private void awaitCache(ClientStorage storage, boolean present) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (Files.exists(WaitingMusic.cacheFile(storage)) != present && System.currentTimeMillis() < deadline) Thread.sleep(20);
		assertEquals(present, Files.exists(WaitingMusic.cacheFile(storage)));
	}

	private void awaitProbe(FakeTransport transport, int count) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (transport.fetches < count && System.currentTimeMillis() < deadline) Thread.sleep(20);
		assertTrue(transport.fetches >= count);
	}

	@Test
	void firstContactStreamsAndCachesSecondContactIsCached() throws Exception {
		ClientStorage storage = storage();
		FakeTransport transport = new FakeTransport(TRACK, true);
		WaitingMusic.Session session = WaitingMusic.start(transport, storage);
		assertEquals(WaitingMusic.Kind.STREAM, session.kind());
		assertArrayEquals(TRACK, session.audio().readAllBytes());
		transport.awaitDone();
		awaitCache(storage, true);
		WaitingMusic.endRun();

		// Second contact: the cache is valid, so the session loops the file and the fetch answers 304.
		FakeTransport conditional = new FakeTransport(TRACK, true);
		WaitingMusic.Session again = WaitingMusic.start(conditional, storage);
		assertEquals(WaitingMusic.Kind.LOOP, again.kind());
		assertEquals(WaitingMusic.cacheFile(storage), again.loopFile());
		awaitProbe(conditional, 1);
		assertTrue(conditional.lastExpected != null, "the probe carries If-None-Match");
		conditional.awaitDone();
		WaitingMusic.endRun();
	}

	@Test
	void withdrawDeletesTheCacheSoTheBundledTrackPlays() throws Exception {
		ClientStorage storage = storage();
		FakeTransport transport = new FakeTransport(TRACK, true);
		WaitingMusic.start(transport, storage);
		transport.awaitDone();
		awaitCache(storage, true);
		WaitingMusic.endRun();

		FakeTransport withdrawn = new FakeTransport(TRACK, false);
		WaitingMusic.Session session = WaitingMusic.start(withdrawn, storage);
		assertEquals(WaitingMusic.Kind.LOOP, session.kind(), "the cached play is not interrupted by the withdrawal probe");
		awaitProbe(withdrawn, 1);
		withdrawn.awaitDone();
		WaitingMusic.endRun();
		awaitCache(storage, false);
	}

	@Test
	void oversizedTracksPlayButNeverCache() throws Exception {
		byte[] big = new byte[(int) (WaitingMusic.MAX_CACHED_TRACK_BYTES + 1024)];
		ClientStorage storage = storage();
		FakeTransport transport = new FakeTransport(big, true);
		WaitingMusic.Session session = WaitingMusic.start(transport, storage);
		assertEquals(WaitingMusic.Kind.STREAM, session.kind());
		assertEquals(big.length, session.audio().readAllBytes().length);
		transport.awaitDone();
		WaitingMusic.endRun();
		awaitCache(storage, false);
	}

	/** A platform-style source: serves or withholds the track, records the conditional probes. */
	private static final class FakeTransport implements PackTransport {
		private final byte[] track;
		private final boolean serverHasTrack;
		private int fetches;
		private String lastExpected;

		FakeTransport(byte[] track, boolean serverHasTrack) {
			this.track = track;
			this.serverHasTrack = serverHasTrack;
		}

		void awaitDone() {
			while (!done) Thread.onSpinWait();
		}

		private volatile boolean done;

		@Override
		public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, OutputStream tap) {
			fetches++;
			lastExpected = expectedSha1Hex;
			if (!serverHasTrack) {
				done = true;
				return CompletableFuture.failedFuture(new IOException("HTTP 404"));
			}
			return CompletableFuture.runAsync(() -> {
				try {
					if (tap != null) tap.write(track);
					Files.write(destination, track);
					done = true;
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}).thenApply(ignored -> new DocumentFetch(destination, false));
		}

		@Override
		public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, IntConsumer progress) {
			return downloadDocument(key, destination, expectedSha1Hex, (OutputStream) null);
		}

		@Override
		public CompletableFuture<Path> downloadFile(byte[] key, Path destination, long offset, IntConsumer progress) {
			return CompletableFuture.failedFuture(new IOException("unused"));
		}

		@Override
		public void abortTransfers() {}

		@Override
		public void close() {}
	}
}
