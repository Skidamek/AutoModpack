package pl.skidam.automodpack_core.utils.cache;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import pl.skidam.automodpack_core.utils.FileInspection;

/** A shared content-keyed mod inspection cache backed by immutable loose records. */
public class ModFileCache extends LooseRecordCache<ModFileCache.ModRecord> {

	private static final SharedCacheRegistry<ModFileCache> REGISTRY = new SharedCacheRegistry<>();

	public static ModFileCache open(Path path) throws IOException {
		return REGISTRY.acquire(path, ModFileCache::new);
	}

	private ModFileCache(Path recordsDirectory) {
		super(recordsDirectory, "mod metadata");
	}

	public FileInspection.Mod getOrComputeMod(Path file, FileCache cache) throws IOException {
		Path absPath = file.toAbsolutePath().normalize();
		String hash = cache.getOrComputeHash(absPath);
		if (hash == null) return null;
		hash = hash.toLowerCase(Locale.ROOT);

		synchronized (lock(hash)) {
			ModRecord cached = readRecord(hash, ModRecord.class);
			if (isComplete(cached)) return cached.at(absPath);

			hash = cache.getOrComputeHash(absPath);
			if (hash == null) return null;
			hash = hash.toLowerCase(Locale.ROOT);
			cached = readRecord(hash, ModRecord.class);
			if (isComplete(cached)) return cached.at(absPath);

			FileInspection.Mod modFile = FileInspection.getMod(absPath, cache);
			if (modFile != null) writeRecord(hash, new ModRecord(modFile));
			return modFile;
		}
	}

	public FileInspection.Mod getModOrNull(Path path, FileCache cache) {
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

	@Override
	protected boolean validate(ModRecord record, String key) {
		return key.equalsIgnoreCase(record.hash);
	}

	@Override
	protected boolean releaseFromRegistry() {
		return REGISTRY.release(recordsDirectory, this);
	}

	static final class ModRecord {
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
