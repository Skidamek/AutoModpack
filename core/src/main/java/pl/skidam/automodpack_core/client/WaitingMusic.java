package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.MissingObjectException;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.storage.StoragePaths;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.ByteFormat;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.Throwables;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The server's custom waiting track, published in the head document as the sha1 of the convention file
 * {@code automodpack/host-modpack/waiting-music.ogg} and stored like any object at {@code objects/<sha1>}. The
 * session starts as soon as the host session exists and resolves to one of three plays: the CAS object at once when
 * its hash is the advertised one (no request at all), a live stream of the object fetch when the store is missing it
 * (decoded as they arrive, promoted for every later sync), or the bundled track when the head advertises none, the
 * object is missing, the fetch fails before playable audio, or the body is over the guardrail. The bundled track never
 * plays while a fetch is still in flight, and a stream that fails mid-play ends in silence rather than in an
 * interrupting switch.
 */
public final class WaitingMusic {

	private static volatile Session current;

	/** The run's live session, or null when no transport owns one; the audio layer reads it at screen open. */
	public static Session current() {
		return current;
	}

	/** Ends the run's session; a later download starts a fresh one. */
	public static void endRun() {
		current = null;
	}

	/** Starts the run's session: a CAS hit plays at once, a fresh track streams in, absence resolves to bundled. */
	public static Session start(PackTransport transport, ClientStorage storage, String advertisedSha1) {
		String sha1 = advertisedSha1 == null ? "" : advertisedSha1.trim().toLowerCase(Locale.ROOT);
		Session existing = current;
		if (existing != null && existing.advertisedSha1.equals(sha1)) return existing;
		Session session = new Session(storage, sha1);
		current = session;
		session.begin(transport);
		return session;
	}

	public enum Kind {
		LOOP, STREAM, BUNDLED
	}

	public static final class Session {
		private final ClientStorage storage;
		private final String advertisedSha1;
		private final LinkedBlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
		private final CompletableFuture<Kind> kind = new CompletableFuture<>();
		private final CompletableFuture<Void> fetchFinished = new CompletableFuture<>();
		private volatile boolean fetchAlive = true;
		private volatile IOException fetchError;
		private volatile byte[] residual;
		private volatile Path loopFile;

		private Session(ClientStorage storage, String advertisedSha1) {
			this.storage = storage;
			this.advertisedSha1 = advertisedSha1;
		}

		/** Blocks until the play decision exists: LOOP plays {@code loopFile()} (null = bundled asset), STREAM plays {@code audio()}. */
		public Kind kind() throws InterruptedException {
			while (true) {
				try {
					Kind decided = kind.get(100, TimeUnit.MILLISECONDS);
					// A finished fetch promotes the object; later plays must loop that file. STREAM is one-shot:
					// the ogg bytes are already consumed and JOrbis cannot reopen them from the middle or from EOF.
					return loopFile != null ? Kind.LOOP : decided;
				} catch (TimeoutException e) {
					// The fetch is still in flight; bundled must not win this wait.
				} catch (ExecutionException e) {
					return loopFile != null ? Kind.LOOP : Kind.BUNDLED;
				}
			}
		}

		/** The live stream of a STREAM session; blocks while the server is slow, ends at EOF, fails when the fetch did. */
		public InputStream audio() {
			return new InputStream() {
				@Override
				public int read() throws IOException {
					byte[] one = new byte[1];
					int read = read(one, 0, 1);
					return read < 0 ? -1 : one[0] & 0xFF;
				}

				@Override
				public int read(byte[] buffer, int offset, int length) throws IOException {
					byte[] chunk = residual;
					while (chunk == null) {
						if (!fetchAlive) {
							if (chunks.isEmpty()) {
								Throwable cause = fetchError;
								if (cause != null) throw new IOException("The custom waiting music stream failed", cause);
								return -1;
							}
							chunk = chunks.poll();
							continue;
						}
						try {
							chunk = chunks.poll(1, TimeUnit.SECONDS);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							throw new IOException("The custom waiting music stream was interrupted", e);
						}
					}
					int copy = Math.min(chunk.length, length);
					System.arraycopy(chunk, 0, buffer, offset, copy);
					residual = copy < chunk.length ? Arrays.copyOfRange(chunk, copy, chunk.length) : null;
					return copy;
				}
			};
		}

		/** The cached file a LOOP session plays; null means the bundled asset. */
		public Path loopFile() {
			return loopFile;
		}

		/** Blocks until the object fetch (or the cache-hit / bundled decision) has released every file it opened. */
		void awaitFetch() throws InterruptedException {
			try {
				fetchFinished.get(20, TimeUnit.SECONDS);
			} catch (TimeoutException e) {
				throw new IllegalStateException("waiting music fetch did not finish", e);
			} catch (ExecutionException ignored) {
			}
		}

		private void completeKind(Kind value) {
			kind.complete(value);
		}

		private void begin(PackTransport transport) {
			if (!HashUtils.isSha1(advertisedSha1)) {
				completeKind(Kind.BUNDLED);
				fetchFinished.complete(null);
				return;
			}
			Path object = storage.objectFile(advertisedSha1);
			try {
				if (Files.isRegularFile(object) && FileIntegrity.matches(object, Files.size(object), advertisedSha1)) {
					loopFile = object;
					completeKind(Kind.LOOP);
					fetchFinished.complete(null);
					return;
				}
			} catch (IOException e) {
				LOGGER.warn("Could not read the cached waiting music object; fetching it again", e);
			}
			try {
				ClientObjectStore.publishOwnership(storage, Set.of(advertisedSha1));
			} catch (IOException e) {
				LOGGER.warn("Could not pin the waiting music object during fetch", e);
			}
			CompletableFuture.runAsync(() -> fetch(transport, object), DownloadClient.NET_EXECUTOR);
		}

		/** The object fetch streams the advertised track; a verified body is promoted into CAS for every later sync. */
		private void fetch(PackTransport transport, Path object) {
			Path temp = null;
			try {
				temp = Files.createTempFile(storage.stagingDirectory(), ".waiting-music.", ".ogg");
				OutputStream tap = new OutputStream() {
					@Override
					public void write(byte[] buffer, int offset, int length) {
						byte[] copy = new byte[length];
						System.arraycopy(buffer, offset, copy, 0, length);
						chunks.offer(copy);
						completeKind(Kind.STREAM);
					}

					@Override
					public void write(int b) {
						write(new byte[]{(byte) b}, 0, 1);
					}
				};
				transport.downloadSmallObject(advertisedSha1.getBytes(StandardCharsets.UTF_8), temp, StoragePaths.WAITING_MUSIC_MAX_BYTES, tap).join();
				if (Files.size(temp) > StoragePaths.WAITING_MUSIC_MAX_BYTES) throw new IOException("The served waiting music exceeds the " + StoragePaths.WAITING_MUSIC_MAX_BYTES + " byte guardrail");
				if (!advertisedSha1.equals(HashUtils.getHash(temp))) throw new IOException("The served waiting music does not match the advertised hash");
				publish(object, temp);
			} catch (Exception e) {
				fetchAlive = false;
				Throwable cause = Throwables.unwrap(e);
				if (cause instanceof MissingObjectException) {
					completeKind(Kind.BUNDLED);
					deleteQuietly(temp);
					return;
				}
				fetchError = cause instanceof IOException io ? io : new IOException(cause);
				completeKind(Kind.BUNDLED);
				LOGGER.warn("The custom waiting music fetch failed", e);
				deleteQuietly(temp);
			} finally {
				fetchAlive = false;
				fetchFinished.complete(null);
			}
		}

		private void publish(Path object, Path temp) throws IOException {
			Path parent = object.getParent();
			if (parent != null) Files.createDirectories(parent);
			try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
				VerifiedFileTransfer.promoteAtomic(temp, object, Files.size(temp), advertisedSha1, cache);
			}
			loopFile = object;
			LOGGER.info("Stored the server's custom waiting music ({})", ByteFormat.formatSize(Files.size(object)));
		}

		private void deleteQuietly(Path path) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException ignored) {
			}
		}
	}
}
