package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.protocol.DocumentFetch;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.ByteFormat;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.Throwables;

/**
 * The server's custom waiting track: the reserved {@code /music} document, fetched conditionally at the start of a
 * download, cached in the client directory forever after, and played by the audio layer whenever the screen opens. Any
 * absence or failure reads as "no custom track" - the bundled one plays, because ambience must never delay the update.
 */
public final class WaitingMusic {
	// The track competes with pack bytes during the most starved moment of the first sync: 4 MiB is roughly three
	// minutes of ogg at a normal music bitrate, and anything larger falls back to the bundled track.
	public static final long MAX_TRACK_BYTES = 4 * 1024 * 1024;

	private WaitingMusic() {}

	public static Path cacheFile(ClientStorage storage) {
		return storage.clientDirectory().resolve("waiting-music.ogg");
	}

	private static Path hashFile(ClientStorage storage) {
		return storage.clientDirectory().resolve("waiting-music.sha1");
	}

	/** The cached track's sha1, or null when nothing (valid) is cached; the conditional fetch's If-None-Match. */
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

	/** Fire-and-forget refresh at download start; the result path (or null) reaches the screen through the caller. */
	public static CompletableFuture<Path> refreshAsync(PackTransport transport, ClientStorage storage) {
		return CompletableFuture.supplyAsync(() -> refresh(transport, storage), DownloadClient.NET_EXECUTOR);
	}

	/** One conditional fetch: 304 keeps the cache, 200 replaces it, 404 withdraws it, anything else keeps it. */
	public static Path refresh(PackTransport transport, ClientStorage storage) {
		Path cache = cacheFile(storage);
		Path temp = null;
		try {
			String expected = cachedSha1(storage);
			temp = Files.createTempFile(cache.toFile().getParentFile().toPath(), ".waiting-music.", ".ogg");
			DocumentFetch fetch = transport.downloadDocument(GenerationHosting.MUSIC_DOCUMENT_KEY.getBytes(StandardCharsets.UTF_8), temp, expected, null).join();
			if (fetch.unchanged()) {
				Files.deleteIfExists(temp);
				return Files.isRegularFile(cache) ? cache : null;
			}
			if (Files.size(temp) > MAX_TRACK_BYTES) {
				LOGGER.warn("The server's custom waiting music exceeds {} bytes; the bundled track stays", MAX_TRACK_BYTES);
				forget(storage, temp);
				return null;
			}
			String sha1 = HashUtils.getHash(temp);
			Files.move(temp, cache, StandardCopyOption.REPLACE_EXISTING);
			Files.writeString(hashFile(storage), sha1, StandardCharsets.UTF_8);
			LOGGER.info("Cached the server's custom waiting music ({})", ByteFormat.formatSize(Files.size(cache)));
			return cache;
		} catch (Exception e) {
			Throwable cause = Throwables.unwrap(e);
			boolean withdrawn = cause instanceof IOException io && io.getMessage() != null && io.getMessage().contains("HTTP 404");
			if (withdrawn) {
				LOGGER.debug("The server offers no custom waiting music; the bundled track stays");
				forget(storage, temp);
			} else {
				LOGGER.warn("The custom waiting music fetch failed; keeping what is cached", e);
				deleteQuietly(temp);
			}
			return null;
		}
	}

	/** The server withdrew the track: the cache goes with it, so the bundled track plays from here on. */
	private static void forget(ClientStorage storage, Path temp) {
		deleteQuietly(temp);
		try {
			Files.deleteIfExists(cacheFile(storage));
			Files.deleteIfExists(hashFile(storage));
		} catch (IOException ignored) {
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
		}
	}
}
