package pl.skidam.automodpack_core.utils.cache;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.utils.FileInspection;

/** A shared content-keyed mod inspection cache backed by immutable loose records. */
public class ModFileCache implements AutoCloseable {

	private static final SharedCacheRegistry<ModFileCache> REGISTRY = new SharedCacheRegistry<>();
	private static final String RECORD_SUFFIX = ".json";

	private final Path recordsDirectory;
	private final Map<String, ModRecord> hotRecords = new HashMap<>();
	private final Object[] locks = new Object[64];

	public static ModFileCache open(Path path) throws IOException {
		return REGISTRY.acquire(path, ModFileCache::new);
	}

	private ModFileCache(Path recordsDirectory) {
		this.recordsDirectory = recordsDirectory;
		for (int i = 0; i < locks.length; i++) locks[i] = new Object();
	}

	public FileInspection.Mod getOrComputeMod(Path file, FileMetadataCache cache) throws IOException {
		Path absPath = file.toAbsolutePath().normalize();
		String hash = cache.getOrComputeHash(absPath);
		if (hash == null) return null;
		int lockIndex = Math.floorMod(hash.hashCode(), locks.length);

		synchronized (locks[lockIndex]) {
			ModRecord cached = readRecord(hash);
			if (isComplete(cached)) return cached.at(absPath);

			hash = cache.getOrComputeHash(absPath);
			if (hash == null) return null;
			cached = readRecord(hash);
			if (isComplete(cached)) return cached.at(absPath);

			FileInspection.Mod modFile = FileInspection.getMod(absPath, cache);
			if (modFile != null) writeRecord(hash, new ModRecord(modFile));
			return modFile;
		}
	}

	/** Re-inspects mod metadata from bytes that are force-rehashed during this call. */
	public FileInspection.Mod reinspectMod(Path file, FileMetadataCache cache) throws IOException {
		Path absPath = file.toAbsolutePath().normalize();
		String hash = cache.rehash(absPath);
		int lockIndex = Math.floorMod(hash.hashCode(), locks.length);
		synchronized (locks[lockIndex]) {
			FileInspection.Mod modFile = FileInspection.getMod(absPath, cache);
			if (modFile != null) writeRecord(hash, new ModRecord(modFile));
			else {
				hotRecords.remove(hash);
				Files.deleteIfExists(recordPath(hash));
			}
			return modFile;
		}
	}

	public FileInspection.Mod getModOrNull(Path path, FileMetadataCache cache) {
		try {
			return getOrComputeMod(path, cache);
		} catch (IOException e) {
			LOGGER.error("Failed to compute mod metadata for path: {}", path, e);
			return null;
		}
	}

	private static boolean isComplete(ModRecord cached) {
		return cached != null && cached.id != null && cached.services != null;
	}

	private ModRecord readRecord(String hash) {
		String normalizedHash = hash.toLowerCase(Locale.ROOT);
		ModRecord hot = hotRecords.get(normalizedHash);
		if (hot != null && normalizedHash.equalsIgnoreCase(hot.hash)) return hot;
		Path recordPath = recordPath(normalizedHash);
		if (!Files.isRegularFile(recordPath, LinkOption.NOFOLLOW_LINKS)) return null;
		try {
			ModRecord record = ConfigTools.read(recordPath, ModRecord.class).orElse(null);
			if (record == null || !normalizedHash.equalsIgnoreCase(record.hash)) return null;
			hotRecords.put(normalizedHash, record);
			return record;
		} catch (RuntimeException e) {
			LOGGER.debug("Ignoring invalid mod metadata cache record: {}", recordPath);
			return null;
		}
	}

	private void writeRecord(String hash, ModRecord record) {
		String normalizedHash = hash.toLowerCase(Locale.ROOT);
		hotRecords.put(normalizedHash, record);
		try {
			ConfigTools.writeAtomic(recordPath(normalizedHash), record);
		} catch (IOException e) {
			LOGGER.debug("Could not persist mod metadata cache record: {}", normalizedHash, e);
		}
	}

	private Path recordPath(String hash) {
		return recordsDirectory.resolve(hash.substring(0, 2)).resolve(hash.substring(2) + RECORD_SUFFIX);
	}

	@Override
	public void close() {
		if (REGISTRY.release(recordsDirectory, this)) hotRecords.clear();
	}

	private static final class ModRecord {
		private Set<String> IDs;
		private String hash;
		private String version;
		private Set<String> deps;
		private Set<ModRecord> nestedMods;
		private String id;
		private Set<String> services;

		private ModRecord() {}

		private ModRecord(FileInspection.Mod mod) {
			IDs = mod.IDs();
			hash = mod.hash();
			version = mod.version();
			deps = mod.deps();
			nestedMods = mod.nestedMods().stream().map(ModRecord::new).collect(Collectors.toSet());
			id = mod.id();
			services = mod.services();
		}

		private FileInspection.Mod at(Path path) {
			Set<FileInspection.Mod> nested = nestedMods == null ? Set.of() : nestedMods.stream().map(record -> record.at(null)).collect(Collectors.toSet());
			return new FileInspection.Mod(IDs == null ? Set.of() : IDs, hash, version, path, deps == null ? Set.of() : deps, nested, id, services == null ? Set.of() : services);
		}
	}
}
