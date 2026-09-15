package pl.skidam.automodpack_core.loader;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.airlift.compress.zstd.ZstdDecompressor;

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
 * impl/manifest.bin}) into the instance's impl-cache worktree and hands back the real-file Path the
 * loaders mount. The hit path is one stamp read plus one git-stat per impl ({@code
 * FileIntegrity.matchesNamed} never reads bytes); any miss regenerates the whole generation, since
 * a solid frame is all-or-nothing: inflate once, verify every slice against the manifest, stage,
 * then publish by renaming the staging directory over the cache directory - a crash mid-write never
 * becomes a stamp hit because the stamp travels inside the renamed tree.
 */
public final class ImplStore {
	private static final String MANIFEST_ENTRY = "impl/manifest.bin";
	private static final String SOLID_ENTRY = "impl/all.zst";
	private static final String STAMP_FILE = "stamp.json";
	/** Stat records of the impl jars; inside the cache directory so a generation wipe takes them along. */
	private static final String RECORDS_DIR = "records";
	/** Sibling staging directory the next generation is built in; published by one rename. */
	private static final String STAGING_SUFFIX = ".staging";
	/** Cross-process lock file guarding the wipe-and-restage, derived from the cache directory name. */
	private static final String LOCK_SUFFIX = ".lock";

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
		ImplManifest manifest = readManifest(outerJar);
		ImplManifest.Entry entry = manifest.entryFor(loader, mcVersion);
		LOGGER.info("AutoModpack target: {}", entry.id());
		Path cacheDir = GameDirectory.current().resolve(client ? StoragePaths.CLIENT_IMPL_CACHE_DIR : StoragePaths.SERVER_IMPL_CACHE_DIR);
		Path implJar = cacheDir.resolve(entry.id() + ".jar");
		Path lockFile = cacheDir.resolveSibling(cacheDir.getFileName() + LOCK_SUFFIX);

		try (FileCache cache = FileCache.open(cacheDir.resolve(RECORDS_DIR))) {
			if (stampHits(cacheDir, manifest, cache)) {
				LOGGER.info("AutoModpack impl cache hit for generation {}", manifest.generation());
				return implJar;
			}
			// One lock file per cache directory serializes concurrent restages (a helper process could
			// race a game boot). Readers never take it: a reader sees either the old published tree or
			// the new one, and a boot that already mounted a jar keeps its open file until it exits.
			FileLocks.withLock(lockFile, () -> {
				if (stampHits(cacheDir, manifest, cache)) return implJar;
				LOGGER.info("AutoModpack impl cache miss - restaging all {} impls for generation {}", manifest.entries().size(), manifest.generation());
				restage(outerJar, manifest, cacheDir, cache);
				return implJar;
			});
		}
		return implJar;
	}

	private static ImplManifest readManifest(Path outerJar) throws IOException {
		try (ZipFile zip = new ZipFile(outerJar.toFile())) {
			ZipEntry manifestEntry = zip.getEntry(MANIFEST_ENTRY);
			if (manifestEntry == null) throw new IllegalStateException("Outer jar " + outerJar + " carries no impl manifest at " + MANIFEST_ENTRY);
			return ImplManifest.parse(zip.getInputStream(manifestEntry).readAllBytes());
		}
	}

	/** Whether the cached worktree is exactly this manifest's generation: the stamp must agree and every impl must pass its stat tripwire. */
	private static boolean stampHits(Path cacheDir, ImplManifest manifest, FileCache cache) throws IOException {
		StampFields stamp = ConfigTools.readState(cacheDir.resolve(STAMP_FILE), StampFields.class, "Impl cache stamp", fields -> {
			if (fields.generation == null || fields.generation.isBlank()) throw new IllegalArgumentException("Impl cache stamp carries no generation");
			return fields;
		}).orElse(null);
		if (stamp == null || !manifest.generation().equalsIgnoreCase(stamp.generation)) return false;
		for (ImplManifest.Entry entry : manifest.entries()) {
			if (!FileIntegrity.matchesNamed(cacheDir.resolve(entry.id() + ".jar"), entry.length(), entry.sha1(), cache)) return false;
		}
		return true;
	}

	private static void restage(Path outerJar, ImplManifest manifest, Path cacheDir, FileCache cache) throws IOException {
		byte[] solid;
		try (ZipFile zip = new ZipFile(outerJar.toFile())) {
			ZipEntry solidEntry = zip.getEntry(SOLID_ENTRY);
			if (solidEntry == null) throw new IllegalStateException("Outer jar " + outerJar + " carries no solid impl blob at " + SOLID_ENTRY);
			byte[] compressed = zip.getInputStream(solidEntry).readAllBytes();
			// The manifest's lengths sum to the solid size: build-side zstd -19 ran over a file, so the
			// frame carries the size too - the manifest is the source of truth and the slice bounds.
			solid = new byte[Math.toIntExact(manifest.totalSize())];
			int decompressed = new ZstdDecompressor().decompress(compressed, 0, compressed.length, solid, 0, solid.length);
			if (decompressed != solid.length) throw new IllegalStateException("Solid impl blob inflated to " + decompressed + " bytes, expected " + solid.length);
		}

		Path staging = cacheDir.resolveSibling(cacheDir.getFileName() + STAGING_SUFFIX);
		FileTrees.delete(staging);
		Files.createDirectories(staging);
		for (ImplManifest.Entry entry : manifest.entries()) {
			byte[] slice = Arrays.copyOfRange(solid, Math.toIntExact(entry.offset()), Math.toIntExact(entry.offset() + entry.length()));
			MessageDigest digest = HashUtils.newSha1Digest();
			String sha1 = HexFormat.of().formatHex(digest.digest(slice));
			if (!sha1.equalsIgnoreCase(entry.sha1())) throw new IllegalStateException("Impl " + entry.id() + " in " + outerJar.getFileName() + " does not match its manifest SHA-1 - the outer jar is corrupt");
			Files.write(staging.resolve(entry.id() + ".jar"), slice);
		}
		StampFields stamp = new StampFields();
		stamp.generation = manifest.generation();
		DurableFiles.writeVolatile(staging.resolve(STAMP_FILE), ConfigTools.GSON.toJson(stamp).getBytes(StandardCharsets.UTF_8));

		// Publish is the rename: nothing at the live path until the whole generation is on disk.
		// On Windows a concurrent reader holding an impl open makes this delete fail - that is a loud
		// crash, never a half-published tree.
		FileTrees.delete(cacheDir);
		FileTrees.moveRecoverableDirectory(staging, cacheDir);
		// Records after publish so they key the final absolute paths; a crash here costs one rehash
		// next boot (matchesImmutable recomputes and republishes), never a wrong hit.
		for (ImplManifest.Entry entry : manifest.entries()) {
			cache.overwriteCache(cacheDir.resolve(entry.id() + ".jar"), entry.sha1());
		}
	}

	/**
	 * The persisted cache stamp: the one generation this tree was staged for. Regenerable, so a corrupt one is set aside and costs one restage. Document class, not record: this graph lands on disk where the game's Gson
	 * cannot read records.
	 */
	public static class StampFields {
		public String generation = "";
	}
}
