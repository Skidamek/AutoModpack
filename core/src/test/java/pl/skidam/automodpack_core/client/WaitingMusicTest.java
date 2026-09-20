package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.protocol.DocumentFetch;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.update.ClientStorage;

/** The custom track's lifecycle: conditional fetch, cache forever, withdraw on 404, bundled past the size cap. */
class WaitingMusicTest {
	private static final byte[] TRACK = "waiting-music-bytes".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path tempDir;

	private FakeTransport transport;

	@BeforeEach
	void setup() {
		transport = new FakeTransport();
	}

	private ClientStorage storage() throws IOException {
		return ClientStorage.open(tempDir.resolve("game"));
	}

	@Test
	void firstContactFetchesAndCachesSecondContactIsUnchanged() throws Exception {
		ClientStorage storage = storage();
		Path cached = WaitingMusic.refresh(transport, storage);
		assertNotNull(cached);
		assertArrayEquals(TRACK, Files.readAllBytes(cached));
		assertEquals(1, transport.fetches);
		assertNull(transport.lastExpected, "the first contact has nothing to be conditional on");

		Path second = WaitingMusic.refresh(transport, storage);
		assertEquals(cached, second);
		assertEquals(2, transport.fetches);
		assertEquals(sha1(TRACK), transport.lastExpected, "the second fetch is conditional on the cached hash");
	}

	@Test
	void withdrawDeletesTheCacheSoTheBundledTrackPlays() throws Exception {
		ClientStorage storage = storage();
		assertNotNull(WaitingMusic.refresh(transport, storage));
		transport.serverHasTrack = false;
		assertNull(WaitingMusic.refresh(transport, storage));
		assertFalse(Files.exists(WaitingMusic.cacheFile(storage)));
	}

	@Test
	void oversizedTracksFallBackToBundled() throws Exception {
		transport.trackBytes = new byte[(int) (WaitingMusic.MAX_TRACK_BYTES + 1)];
		ClientStorage storage = storage();
		assertNull(WaitingMusic.refresh(transport, storage));
		assertFalse(Files.exists(WaitingMusic.cacheFile(storage)));
	}

	private static String sha1(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			return HexFormat.of().formatHex(digest.digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static final class FakeTransport implements PackTransport {
		byte[] trackBytes = TRACK;
		boolean serverHasTrack = true;
		int fetches;
		String lastExpected;

		@Override
		public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, IntConsumer progress) {
			fetches++;
			lastExpected = expectedSha1Hex;
			if (!serverHasTrack) return CompletableFuture.failedFuture(new IOException("HTTP 404"));
			String served = sha1(trackBytes);
			if (served.equals(expectedSha1Hex)) return CompletableFuture.completedFuture(new DocumentFetch(null, true));
			try {
				Files.write(destination, trackBytes);
			} catch (IOException e) {
				return CompletableFuture.failedFuture(e);
			}
			return CompletableFuture.completedFuture(new DocumentFetch(destination, false));
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
