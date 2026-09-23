package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.protocol.DocumentFetch;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.MissingObjectException;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.storage.StoragePaths;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.HashUtils;

/** The custom track's lifecycle: the advertised hash is the cache key, so a hit never asks, a change streams, absence withdraws. */
class WaitingMusicTest {
	private static final byte[] TRACK = "waiting-music-bytes".getBytes(StandardCharsets.UTF_8);
	private static final byte[] NEW_TRACK = "waiting-music-bytes-v2".getBytes(StandardCharsets.UTF_8);
	private static final String TRACK_SHA1 = HashUtils.sha1(TRACK);
	private static final String NEW_TRACK_SHA1 = HashUtils.sha1(NEW_TRACK);

	@TempDir
	Path tempDir;

	private ClientStorage storage() throws IOException {
		return ClientStorage.open(tempDir.resolve("game"));
	}

	private Path sidecar(ClientStorage storage) {
		return storage.clientDirectory().resolve(StoragePaths.WAITING_MUSIC_FILE + ".sha1");
	}

	private void awaitCache(ClientStorage storage, boolean present) throws InterruptedException {
		// The sidecar is written after the cache move: awaiting it means the track is fully published.
		long deadline = System.currentTimeMillis() + 5000;
		boolean sidecar = Files.exists(sidecar(storage));
		while (sidecar != present && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
			sidecar = Files.exists(sidecar(storage));
		}
		assertEquals(present, sidecar);
	}

	@Test
	void firstContactStreamsAndCachesSecondContactNeverAsks() throws Exception {
		ClientStorage storage = storage();
		FakeTransport transport = new FakeTransport(TRACK);
		WaitingMusic.Session session = WaitingMusic.start(transport, storage, TRACK_SHA1);
		assertEquals(WaitingMusic.Kind.STREAM, session.kind());
		assertArrayEquals(TRACK, session.audio().readAllBytes());
		transport.awaitDone();
		awaitCache(storage, true);
		assertEquals(WaitingMusic.cacheFile(storage), session.loopFile(), "a finished stream loops the cached track");
		assertEquals(StoragePaths.WAITING_MUSIC_MAX_BYTES, transport.lastLimit, "the fetch carries the waiting-track guardrail");
		WaitingMusic.endRun();

		// Second contact: the advertised hash IS the cache key, so the cached track plays and no request is made at all.
		FakeTransport quiet = new FakeTransport(TRACK);
		WaitingMusic.Session again = WaitingMusic.start(quiet, storage, TRACK_SHA1);
		assertEquals(WaitingMusic.Kind.LOOP, again.kind());
		assertEquals(WaitingMusic.cacheFile(storage), again.loopFile());
		quiet.awaitIdle();
		assertEquals(0, quiet.fetches.get(), "a hash hit must not touch the wire");
		WaitingMusic.endRun();
	}

	@Test
	void noAdvertisedTrackWithdrawsTheCacheSoTheBundledTrackPlays() throws Exception {
		ClientStorage storage = storage();
		FakeTransport transport = new FakeTransport(TRACK);
		WaitingMusic.start(transport, storage, TRACK_SHA1);
		transport.awaitDone();
		awaitCache(storage, true);
		WaitingMusic.endRun();

		FakeTransport withdrawn = new FakeTransport(TRACK);
		WaitingMusic.Session session = WaitingMusic.start(withdrawn, storage, "");
		assertEquals(WaitingMusic.Kind.BUNDLED, session.kind());
		withdrawn.awaitIdle();
		assertEquals(0, withdrawn.fetches.get());
		awaitCache(storage, false);
	}

	@Test
	void aChangedTrackStreamsInsteadOfPlayingTheStaleCache() throws Exception {
		ClientStorage storage = storage();
		FakeTransport transport = new FakeTransport(TRACK);
		WaitingMusic.start(transport, storage, TRACK_SHA1);
		transport.awaitDone();
		awaitCache(storage, true);
		WaitingMusic.endRun();

		FakeTransport changed = new FakeTransport(NEW_TRACK);
		WaitingMusic.Session session = WaitingMusic.start(changed, storage, NEW_TRACK_SHA1);
		assertEquals(WaitingMusic.Kind.STREAM, session.kind());
		assertArrayEquals(NEW_TRACK, session.audio().readAllBytes());
		changed.awaitDone();
		awaitCache(storage, true);
		assertEquals(NEW_TRACK_SHA1, Files.readString(sidecar(storage)).trim());
		WaitingMusic.endRun();
	}

	@Test
	void aMissingObjectFailsToBundledAndWithdrawsTheStaleCache() throws Exception {
		ClientStorage storage = storage();
		FakeTransport transport = new FakeTransport(TRACK);
		WaitingMusic.start(transport, storage, TRACK_SHA1);
		transport.awaitDone();
		awaitCache(storage, true);
		WaitingMusic.endRun();

		FakeTransport missing = new FakeTransport(NEW_TRACK);
		missing.serverHasTrack = false;
		WaitingMusic.Session session = WaitingMusic.start(missing, storage, NEW_TRACK_SHA1);
		awaitCache(storage, false);
		missing.awaitDone();
		assertEquals(WaitingMusic.Kind.BUNDLED, session.kind());
		WaitingMusic.endRun();
	}

	@Test
	void aBodyThatBreaksItsHashIsNotCached() throws Exception {
		ClientStorage storage = storage();
		FakeTransport transport = new FakeTransport(TRACK);
		transport.corrupt = true;
		WaitingMusic.Session session = WaitingMusic.start(transport, storage, TRACK_SHA1);
		transport.awaitDone();
		assertNull(session.loopFile(), "a broken body never becomes the loop file");
		awaitCache(storage, false);
		WaitingMusic.endRun();
	}

	/** A platform-style source: serves the object body with its tap, records every fetch. */
	private static final class FakeTransport implements PackTransport {
		private final byte[] track;
		private volatile boolean serverHasTrack = true;
		private volatile boolean corrupt;
		private final AtomicInteger fetches = new AtomicInteger();
		private volatile long lastLimit = -1;
		private volatile boolean done;

		FakeTransport(byte[] track) {
			this.track = track;
		}

		void awaitDone() throws InterruptedException {
			long deadline = System.currentTimeMillis() + 5000;
			while (!done && System.currentTimeMillis() < deadline) Thread.sleep(5);
			assertTrue(done, "the fetch never finished");
		}

		/** A fetch submitted by begin() is enqueued on the wire executor ahead of the handshake task, so its attempt is counted when this returns. */
		void awaitIdle() throws Exception {
			CompletableFuture<Void> handshake = new CompletableFuture<>();
			CompletableFuture.runAsync(() -> handshake.complete(null), DownloadClient.NET_EXECUTOR).get(5, TimeUnit.SECONDS);
		}

		@Override
		public CompletableFuture<Path> downloadSmallObject(byte[] key, Path destination, long maxBytes, OutputStream tap) {
			lastLimit = maxBytes;

			fetches.incrementAndGet();
			if (!serverHasTrack) {
				done = true;
				return CompletableFuture.failedFuture(new MissingObjectException());
			}
			byte[] served = corrupt ? "not-the-advertised-bytes".getBytes(StandardCharsets.UTF_8) : track;
			return CompletableFuture.runAsync(() -> {
				try {
					if (tap != null) tap.write(served);
					Files.write(destination, served);
					done = true;
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}).thenApply(ignored -> destination);
		}

		@Override
		public CompletableFuture<Path> downloadObject(byte[] key, Path destination, long fileSize, IntConsumer progress) {
			return CompletableFuture.failedFuture(new IOException("whole objects are not part of this test"));
		}

		@Override
		public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, IntConsumer progress) {
			return CompletableFuture.failedFuture(new IOException("unused"));
		}

		@Override
		public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, OutputStream tap) {
			return CompletableFuture.failedFuture(new IOException("unused"));
		}

		@Override
		public String windowSummary() {
			return "no window";
		}

		@Override
		public void abortTransfers() {}

		@Override
		public void close() {}
	}
}
