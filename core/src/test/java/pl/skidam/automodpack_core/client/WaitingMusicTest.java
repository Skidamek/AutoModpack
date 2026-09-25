package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.protocol.DocumentFetch;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.MissingObjectException;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.storage.StoragePaths;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.HashUtils;

/** The custom track's lifecycle: the advertised hash is the CAS key, so a hit never asks, a change streams, absence is bundled. */
class WaitingMusicTest {
	private static final byte[] TRACK = "waiting-music-bytes".getBytes(StandardCharsets.UTF_8);
	private static final byte[] NEW_TRACK = "waiting-music-bytes-v2".getBytes(StandardCharsets.UTF_8);
	private static final String TRACK_SHA1 = HashUtils.sha1(TRACK);
	private static final String NEW_TRACK_SHA1 = HashUtils.sha1(NEW_TRACK);

	@TempDir
	Path tempDir;

	@AfterEach
	void tearDown() {
		WaitingMusic.endRun();
	}

	private ClientStorage storage() throws Exception {
		return TestDataRoot.open(tempDir.resolve("game-" + UUID.randomUUID()), tempDir.resolve("data-" + UUID.randomUUID()));
	}

	private Path object(ClientStorage storage, String sha1) {
		return storage.objectFile(sha1);
	}

	private void awaitObject(ClientStorage storage, String sha1, boolean present) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		boolean exists = Files.exists(object(storage, sha1));
		while (exists != present && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
			exists = Files.exists(object(storage, sha1));
		}
		assertEquals(present, exists);
	}

	@Test
	void firstContactStreamsAndCachesSecondContactNeverAsks() throws Exception {
		ClientStorage storage = storage();
		FakeTransport transport = new FakeTransport(TRACK);
		WaitingMusic.Session session = WaitingMusic.start(transport, storage, TRACK_SHA1);
		assertEquals(WaitingMusic.Kind.STREAM, session.kind());
		assertArrayEquals(TRACK, session.audio().readAllBytes());
		transport.awaitDone();
		awaitObject(storage, TRACK_SHA1, true);
		assertEquals(object(storage, TRACK_SHA1), session.loopFile(), "a finished stream loops the stored object");
		assertEquals(WaitingMusic.Kind.LOOP, session.kind(), "once the object is stored the same session must loop it, not reopen the consumed stream");
		assertEquals(StoragePaths.WAITING_MUSIC_MAX_BYTES, transport.lastLimit, "the fetch carries the waiting-track guardrail");
		WaitingMusic.endRun();

		FakeTransport quiet = new FakeTransport(TRACK);
		WaitingMusic.Session again = WaitingMusic.start(quiet, storage, TRACK_SHA1);
		assertEquals(WaitingMusic.Kind.LOOP, again.kind());
		assertEquals(object(storage, TRACK_SHA1), again.loopFile());
		quiet.awaitIdle();
		assertEquals(0, quiet.fetches.get(), "a hash hit must not touch the wire");
		WaitingMusic.endRun();
	}

	@Test
	void noAdvertisedTrackPlaysBundledAndLeavesStoredObjectsAlone() throws Exception {
		ClientStorage storage = storage();
		FakeTransport transport = new FakeTransport(TRACK);
		WaitingMusic.start(transport, storage, TRACK_SHA1);
		transport.awaitDone();
		awaitObject(storage, TRACK_SHA1, true);
		WaitingMusic.endRun();

		FakeTransport withdrawn = new FakeTransport(TRACK);
		WaitingMusic.Session session = WaitingMusic.start(withdrawn, storage, "");
		assertEquals(WaitingMusic.Kind.BUNDLED, session.kind());
		withdrawn.awaitIdle();
		assertEquals(0, withdrawn.fetches.get());
		awaitObject(storage, TRACK_SHA1, true);
	}

	@Test
	void aChangedTrackStreamsInsteadOfPlayingTheStaleObject() throws Exception {
		ClientStorage storage = storage();
		FakeTransport transport = new FakeTransport(TRACK);
		WaitingMusic.start(transport, storage, TRACK_SHA1);
		transport.awaitDone();
		awaitObject(storage, TRACK_SHA1, true);
		WaitingMusic.endRun();

		FakeTransport changed = new FakeTransport(NEW_TRACK);
		WaitingMusic.Session session = WaitingMusic.start(changed, storage, NEW_TRACK_SHA1);
		assertEquals(WaitingMusic.Kind.STREAM, session.kind());
		assertArrayEquals(NEW_TRACK, session.audio().readAllBytes());
		changed.awaitDone();
		awaitObject(storage, NEW_TRACK_SHA1, true);
		assertTrue(Files.exists(object(storage, TRACK_SHA1)), "the previous pack's object stays in CAS");
		WaitingMusic.endRun();
	}

	@Test
	void aMissingObjectFailsToBundledAndLeavesOtherObjects() throws Exception {
		ClientStorage storage = storage();
		FakeTransport transport = new FakeTransport(TRACK);
		WaitingMusic.start(transport, storage, TRACK_SHA1);
		transport.awaitDone();
		awaitObject(storage, TRACK_SHA1, true);
		WaitingMusic.endRun();

		FakeTransport missing = new FakeTransport(NEW_TRACK);
		missing.serverHasTrack = false;
		WaitingMusic.Session session = WaitingMusic.start(missing, storage, NEW_TRACK_SHA1);
		missing.awaitDone();
		assertEquals(WaitingMusic.Kind.BUNDLED, session.kind());
		awaitObject(storage, TRACK_SHA1, true);
		awaitObject(storage, NEW_TRACK_SHA1, false);
		WaitingMusic.endRun();
	}

	@Test
	void aBodyThatBreaksItsHashIsNotStored() throws Exception {
		ClientStorage storage = storage();
		FakeTransport transport = new FakeTransport(TRACK);
		transport.corrupt = true;
		WaitingMusic.Session session = WaitingMusic.start(transport, storage, TRACK_SHA1);
		transport.awaitDone();
		assertNull(session.loopFile(), "a broken body never becomes the loop file");
		awaitObject(storage, TRACK_SHA1, false);
		WaitingMusic.endRun();
	}

	@Test
	void aSecondStartWithTheSameHashDoesNotFetchAgain() throws Exception {
		ClientStorage storage = storage();
		FakeTransport transport = new FakeTransport(TRACK);
		WaitingMusic.Session first = WaitingMusic.start(transport, storage, TRACK_SHA1);
		WaitingMusic.Session second = WaitingMusic.start(transport, storage, TRACK_SHA1);
		assertSame(first, second);
		assertEquals(WaitingMusic.Kind.STREAM, first.kind());
		transport.awaitDone();
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
		public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, PackTransport.DocumentConditional conditional, IntConsumer progress) {
			return CompletableFuture.failedFuture(new IOException("unused"));
		}

		@Override
		public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, PackTransport.DocumentConditional conditional, OutputStream tap) {
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
