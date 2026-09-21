package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.storage.StoragePaths;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.ByteFormat;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.Throwables;

/**
 * The server's custom waiting track, published in the head document as the sha1 of the convention file
 * {@code automodpack/host-modpack/waiting-music.ogg} and served like any object at {@code objects/<sha1>}. The
 * session starts eagerly at download begin and resolves to one of three plays: the cached track at once when its
 * hash is the advertised one (no request at all), a live stream of the object fetch when the cache is stale
 * (decoded as they arrive, cached for every later sync), or the bundled track when the head advertises none, the
 * object is missing, or the fetch fails. The bundled track never plays while a fetch is still in flight, and a
 * stream that fails mid-play ends in silence rather than in an interrupting switch.
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

	/** Starts the run's session: the cached track plays at once, a fresh track streams in, absence resolves to bundled. */
	public static Session start(PackTransport transport, ClientStorage storage, String advertisedSha1) {
		Session session = new Session(storage, advertisedSha1 == null ? "" : advertisedSha1.trim().toLowerCase(Locale.ROOT));
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
		private volatile boolean fetchAlive = true;
		private volatile IOException fetchError;
		private volatile byte[] residual;
		private volatile Path loopFile;

		private Session(ClientStorage storage, String advertisedSha1) {
			this.storage = storage;
			this.advertisedSha1 = advertisedSha1;
		}

		/** Blocks until the play decision exists: LOOP plays {@code loopFile()} (null = bundled asset), STREAM plays {@code audio()}. */
		public Kind kind() {
			try {
				return kind.get(30, TimeUnit.SECONDS);
			} catch (Exception e) {
				return Kind.BUNDLED;
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

		private void completeKind(Kind value) {
			synchronized (kind) {
				if (!kind.isDone()) kind.complete(value);
			}
		}

		private void begin(PackTransport transport) {
			Path cache = cacheFile(storage);
			if (!HashUtils.isSha1(advertisedSha1)) {
				// The head advertises no track: bundled plays and any withdrawn cache goes with it.
				completeKind(Kind.BUNDLED);
				forget(cache);
				return;
			}
			if (advertisedSha1.equals(cachedSha1(storage))) {
				// The cached track is this sync's music, and its hash is the cache key: nothing to request at all.
				loopFile = cache;
				kind.complete(Kind.LOOP);
				return;
			}
			CompletableFuture.runAsync(() -> fetch(transport, cache), DownloadClient.NET_EXECUTOR);
		}

		/** The object fetch streams the advertised track; a verified body is cached for every later sync. */
		private void fetch(PackTransport transport, Path cache) {
			Path temp = null;
			try {
				temp = Files.createTempFile(cache.getParent(), ".waiting-music.", ".ogg");
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
				transport.downloadFile(advertisedSha1.getBytes(StandardCharsets.UTF_8), temp, 0L, -1L, null, tap, false, StoragePaths.WAITING_MUSIC_MAX_BYTES, 0).join();
				// The object request judges ranges, not the whole; the advertised hash is judged here before caching.
				if (!advertisedSha1.equals(sha1(temp))) throw new IOException("The served waiting music does not match the advertised hash");
				publish(cache, temp);
			} catch (Exception e) {
				fetchAlive = false;
				Throwable cause = Throwables.unwrap(e);
				if (cause instanceof IOException io && io.getMessage() != null && io.getMessage().contains("HTTP 404")) {
					// The host withdrew the track: whatever streamed finishes, the bundled track follows it.
					completeKind(Kind.BUNDLED);
					forget(cache);
					deleteQuietly(temp);
					return;
				}
				fetchError = cause instanceof IOException io ? io : new IOException(cause);
				completeKind(Kind.BUNDLED); // a failure before the first byte: bundled plays
				LOGGER.warn("The custom waiting music fetch failed", e);
				deleteQuietly(temp);
			} finally {
				fetchAlive = false;
			}
		}

		/** Admits the verified track to the cache; the head guardrail already rejected oversized tracks before a body byte arrived. */
		private void publish(Path cache, Path temp) throws IOException {
			if (Files.size(temp) > StoragePaths.WAITING_MUSIC_MAX_BYTES) {
				LOGGER.warn("The server's custom waiting music exceeds {} bytes despite the response head; it played but will not be cached", StoragePaths.WAITING_MUSIC_MAX_BYTES);
				Files.deleteIfExists(temp);
				return;
			}
			Files.move(temp, cache, StandardCopyOption.REPLACE_EXISTING);
			Files.writeString(hashFile(storage), advertisedSha1, StandardCharsets.UTF_8);
			loopFile = cache; // a finished stream loops the cached track instead of ending in silence
			LOGGER.info("Cached the server's custom waiting music ({})", ByteFormat.formatSize(Files.size(cache)));
		}

		private void forget(Path cache) {
			try {
				Files.deleteIfExists(cache);
				Files.deleteIfExists(hashFile(storage));
			} catch (IOException ignored) {
			}
		}

		private void deleteQuietly(Path path) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException ignored) {
			}
		}
	}

	public static Path cacheFile(ClientStorage storage) {
		return storage.clientDirectory().resolve(StoragePaths.WAITING_MUSIC_FILE);
	}

	private static Path hashFile(ClientStorage storage) {
		return storage.clientDirectory().resolve(StoragePaths.WAITING_MUSIC_FILE + ".sha1");
	}

	/** The cached track's sha1, or null when nothing (valid) is cached; equal to the advertised hash means the cache plays. */
	public static String cachedSha1(ClientStorage storage) {
		Path cache = cacheFile(storage);
		Path hash = hashFile(storage);
		try {
			if (!Files.isRegularFile(cache) || !Files.isRegularFile(hash)) return null;
			String sha1 = Files.readString(hash, StandardCharsets.UTF_8).trim();
			return HashUtils.getHash(cache).equals(sha1) ? sha1 : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static String sha1(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
