package pl.skidam.automodpack_core.loader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.airlift.compress.zstd.ZstdInputStream;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.storage.StoragePaths;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileLocks;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.JarUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * Selects this launch's game impl out of the one jar's solid blob ({@code impl/all.zst} + {@code
 * impl/manifest.json}) into the instance's impl-cache worktree and hands back the real-file Path the
 * loaders mount. The hit path is one stamp read plus one git-stat for the active impl ({@code
 * FileIntegrity.matchesNamed} never reads bytes); any miss (version/loader switch or outer-jar update)
 * streams only the requested slice out of the solid blob, verifies its SHA-1, stages it, then
 * publishes by replacing the cache directory - removing any previous version's impl jar atomically.
 */
public final class ImplStore {
	private static final Logger LOGGER = LogManager.getLogger(ImplStore.class);

	private static final String MANIFEST_ENTRY = "impl/manifest.json";
	private static final String SOLID_ENTRY = "impl/all.zst";
	private static final String STAMP_FILE = "stamp.json";
	/** Stat records of the active impl jar; inside the cache directory so a full wipe takes them along. */
	private static final String RECORDS_DIR = "records";
	/** Sibling staging directory the next staged set is built in; published by one rename. */
	private static final String STAGING_SUFFIX = ".staging";
	/** Cross-process lock file guarding the wipe-and-restage, derived from the cache directory name. */
	private static final String LOCK_SUFFIX = ".lock";
	private static final int STREAM_BUFFER_BYTES = 64 * 1024;

	private ImplStore() {}

	/**
	 * Returns a real-file Path for this launch's impl: the running loader and Minecraft version are resolved
	 * against the manifest's covered versions ({@code TargetId} crashes on an unusable version, {@link
	 * ImplManifest#entryFor} on an uncovered one - both are broken launches and crash instead of guessing).
	 */
	public static Path select(Class<?> outerClass, String loader, String mcVersion, boolean client) throws IOException {
		// Crashes on an unusable loader/version shape before anything else runs; entryFor owns the coverage decision.
		TargetId.id(loader, mcVersion);
		Path outerJar = JarUtils.getJarPath(outerClass);
		Path cacheDir = GameDirectory.current().resolve(client ? StoragePaths.CLIENT_IMPL_CACHE_DIR : StoragePaths.SERVER_IMPL_CACHE_DIR);
		return select(outerJar, loader, mcVersion, cacheDir);
	}

	static Path select(Path outerJar, String loader, String mcVersion, Path cacheDir) throws IOException {
		ImplManifest manifest = readManifest(outerJar);
		ImplManifest.Entry entry = manifest.entryFor(loader, mcVersion);
		Path implJar = cacheDir.resolve(entry.id() + ".jar");
		Path lockFile = cacheDir.resolveSibling(cacheDir.getFileName() + LOCK_SUFFIX);

		try (FileCache cache = FileCache.open(cacheDir.resolve(RECORDS_DIR))) {
			if (stampHits(cacheDir, manifest, entry, cache)) {
				LOGGER.debug("AutoModpack impl cache hit for {} ({})", entry.id(), manifest.digest());
				return implJar;
			}
			// One lock file per cache directory serializes concurrent restages (a helper process could
			// race a game boot). Readers never take it: a reader sees either the old published tree or
			// the new one, and a boot that already mounted a jar keeps its open file until it exits.
			FileLocks.withLock(lockFile, () -> {
				if (stampHits(cacheDir, manifest, entry, cache)) return implJar;
				LOGGER.debug("AutoModpack impl cache miss - restaging {} for digest {}", entry.id(), manifest.digest());
				restage(outerJar, manifest, entry, cacheDir, cache);
				return implJar;
			});
		}
		return implJar;
	}

	/**
	 * Same, from the launch facts a loader generation captured into {@link EarlyLaunchEnvironment}:
	 * crashes before any mount when the launch never captured them ({@code TargetId} owns the
	 * unusable-version crash), so every loader's mount site is one call.
	 */
	public static Path select(Class<?> outerClass, String loader) throws IOException {
		Boolean client = EarlyLaunchEnvironment.IS_CLIENT;
		if (client == null) throw new IllegalStateException("AutoModpack cannot tell client from server before mounting the impl jar");
		return select(outerClass, loader, EarlyLaunchEnvironment.MC_VERSION, client);
	}

	private static ImplManifest readManifest(Path outerJar) throws IOException {
		try (ZipFile zip = new ZipFile(outerJar.toFile())) {
			ZipEntry manifestEntry = zip.getEntry(MANIFEST_ENTRY);
			if (manifestEntry == null) throw new IllegalStateException("Outer jar " + outerJar + " carries no impl manifest at " + MANIFEST_ENTRY);
			return ImplManifest.parse(zip.getInputStream(manifestEntry).readAllBytes());
		}
	}

	/** Whether the cached worktree holds exactly this manifest digest and active impl: the stamp must agree and the impl must pass its stat tripwire. */
	private static boolean stampHits(Path cacheDir, ImplManifest manifest, ImplManifest.Entry entry, FileCache cache) throws IOException {
		StampFields stamp = ConfigTools.readState(cacheDir.resolve(STAMP_FILE), StampFields.class, "Impl cache stamp", fields -> {
			if (fields.digest == null || fields.digest.isBlank()) throw new IllegalArgumentException("Impl cache stamp carries no digest");
			return fields;
		}).orElse(null);
		if (stamp == null || !manifest.digest().equalsIgnoreCase(stamp.digest) || !entry.id().equals(stamp.entryId)) return false;
		return FileIntegrity.matchesNamed(cacheDir.resolve(entry.id() + ".jar"), entry.length(), entry.sha1(), cache);
	}

	private static void restage(Path outerJar, ImplManifest manifest, ImplManifest.Entry entry, Path cacheDir, FileCache cache) throws IOException {
		Path staging = cacheDir.resolveSibling(cacheDir.getFileName() + STAGING_SUFFIX);
		FileTrees.delete(staging);
		Files.createDirectories(staging);

		Path stagedJar = staging.resolve(entry.id() + ".jar");
		try (ZipFile zip = new ZipFile(outerJar.toFile())) {
			ZipEntry solidEntry = zip.getEntry(SOLID_ENTRY);
			if (solidEntry == null) throw new IllegalStateException("Outer jar " + outerJar + " carries no solid impl blob at " + SOLID_ENTRY);
			try (InputStream zstd = new ZstdInputStream(zip.getInputStream(solidEntry)); OutputStream out = Files.newOutputStream(stagedJar)) {
				byte[] buffer = new byte[STREAM_BUFFER_BYTES];
				skipExact(zstd, entry.offset(), buffer, entry.id());
				String sha1 = copyAndHashExact(zstd, out, entry.length(), buffer, entry.id());
				if (!sha1.equalsIgnoreCase(entry.sha1()))
					throw new IllegalStateException("Impl " + entry.id() + " in " + outerJar.getFileName() + " does not match its manifest SHA-1 - the outer jar is corrupt");
			}
		}

		StampFields stamp = new StampFields();
		stamp.digest = manifest.digest();
		stamp.entryId = entry.id();
		DurableFiles.writeVolatile(staging.resolve(STAMP_FILE), ConfigTools.GSON.toJson(stamp).getBytes(StandardCharsets.UTF_8));

		// Publish is the rename: nothing at the live path until the whole tree is on disk, and any
		// previous version's impl jar is wiped along with the old cache directory.
		// On Windows a concurrent reader holding an impl open makes this delete fail - that is a loud
		// crash, never a half-published tree.
		FileTrees.delete(cacheDir);
		FileTrees.moveRecoverableDirectory(staging, cacheDir);
		// Record after publish so it keys the final absolute path; a crash here costs one rehash
		// next boot (matchesImmutable recomputes and republishes), never a wrong hit.
		cache.overwriteCache(cacheDir.resolve(entry.id() + ".jar"), entry.sha1());
	}

	private static void skipExact(InputStream in, long bytes, byte[] buffer, String entryId) throws IOException {
		long remaining = bytes;
		while (remaining > 0) {
			int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
			if (read < 0) throw new IllegalStateException("Solid impl blob ended early while seeking to " + entryId);
			remaining -= read;
		}
	}

	private static String copyAndHashExact(InputStream in, OutputStream out, long bytes, byte[] buffer, String entryId) throws IOException {
		MessageDigest digest = HashUtils.newSha1Digest();
		long remaining = bytes;
		while (remaining > 0) {
			int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
			if (read < 0) throw new IllegalStateException("Solid impl blob ended early while reading " + entryId);
			digest.update(buffer, 0, read);
			out.write(buffer, 0, read);
			remaining -= read;
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * The persisted cache stamp: the manifest digest and single impl id this tree was staged for. Regenerable, so a corrupt one is set aside and costs one restage. Document class, not record: this graph lands on disk
	 * where the game's Gson
	 * cannot read records.
	 */
	public static class StampFields {
		public String digest = "";
		public String entryId = "";
	}
}
