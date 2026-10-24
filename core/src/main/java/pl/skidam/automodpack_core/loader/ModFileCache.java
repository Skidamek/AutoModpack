package pl.skidam.automodpack_core.loader;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.automodpack_core.utils.cache.LooseRecordCache;
import pl.skidam.automodpack_core.utils.cache.SharedCacheRegistry;

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
		return getOrComputeMod(file, null, cache);
	}

	public FileInspection.Mod getOrComputeMod(Path file, String knownSha1, FileCache cache) throws IOException {
		return inspect(file, knownSha1, cache).metadata();
	}

	public FileInspection.Mod getModOrNull(Path path, FileCache cache) {
		return getModOrNull(path, null, cache);
	}

	/** The metadata when there is any, with an unreadable file reading as none. */
	public FileInspection.Mod getModOrNull(Path path, String knownSha1, FileCache cache) {
		try {
			return inspect(path, knownSha1, cache).metadata();
		} catch (IOException e) {
			LOGGER.error("Failed to compute mod metadata for path: {}", path, e);
			return null;
		}
	}

	/** Whether the bytes are a mod jar and the metadata when there is any, from one mount; a service-only jar is a mod without metadata. */
	public FileInspection.Inspection inspect(Path path, FileCache cache) throws IOException {
		return inspect(path, null, cache);
	}

	public FileInspection.Inspection inspect(Path path, String knownSha1, FileCache cache) throws IOException {
		Path absPath = path.toAbsolutePath().normalize();
		String hash = contentHash(absPath, knownSha1, cache);
		if (hash == null) return new FileInspection.Inspection(false, null, Set.of());

		synchronized (lock(hash)) {
			ModRecord cached = readRecord(hash, ModRecord.class);
			if (cached != null) return cached.at(absPath);

			hash = contentHash(absPath, knownSha1, cache);
			if (hash == null) return new FileInspection.Inspection(false, null, Set.of());
			cached = readRecord(hash, ModRecord.class);
			if (cached != null) return cached.at(absPath);

			// A service-only jar is a verdict too, so it is recorded like any other: without it every scan reopened it.
			ModRecord record = new ModRecord(hash, FileInspection.inspect(absPath, cache));
			writeRecord(hash, record);
			return record.at(absPath);
		}
	}

	private static String contentHash(Path file, String knownSha1, FileCache cache) throws IOException {
		if (knownSha1 != null && HashUtils.isSha1(knownSha1)) {
			String sha1 = HashUtils.normalizeSha1(knownSha1);
			if (FileIntegrity.matchesNamed(file, Files.size(file), sha1, cache)) return sha1;
		}
		String hash = cache.getOrComputeHash(file);
		return hash == null ? null : hash.toLowerCase(Locale.ROOT);
	}

	@Override
	protected boolean belongsTo(ModRecord record, String key) {
		return record != null && key.equalsIgnoreCase(record.hash);
	}

	/** Incomplete metadata records and records without cached nested entry paths are rebuilt rather than answered. */
	@Override
	protected boolean isCurrent(ModRecord record) {
		return record.services != null && clientPresenceOf(record.clientPresence) != null && record.environmentFromJarKnown
				&& (record.id == null || (record.isMod && environmentOf(record.environment) != null && nestedPathsCurrent(record)));
	}

	private static boolean nestedPathsCurrent(ModRecord record) {
		if (record.nestedMods == null) return false;
		for (ModRecord nested : record.nestedMods) {
			try {
				if (nested.entryPath == null || !LogicalPath.requireCanonical(nested.entryPath).equals(nested.entryPath) || !nestedPathsCurrent(nested)) return false;
			} catch (IllegalArgumentException e) {
				return false;
			}
		}
		return true;
	}

	@Override
	protected boolean releaseFromRegistry() {
		return REGISTRY.release(recordsDirectory, this);
	}

	static final class ModRecord {
		private boolean isMod;
		private Set<String> IDs;
		private String hash;
		private String version;
		private Set<String> deps;
		private Set<ModRecord> nestedMods;
		private String entryPath;
		private String id;
		private Set<String> services;
		private String environment;
		private String clientPresence;
		/** Written by every record, so one that inferred environment from a loader dependency side is rebuilt. */
		private boolean environmentFromJarKnown;

		private ModRecord() {}

		private ModRecord(String contentHash, FileInspection.Inspection inspection) {
			this(contentHash, inspection, null);
		}

		private ModRecord(String contentHash, FileInspection.Inspection inspection, String entryPath) {
			hash = contentHash;
			this.entryPath = entryPath;
			isMod = inspection.mod();
			services = inspection.services();
			environmentFromJarKnown = true;
			clientPresence = inspection.clientPresence().name();
			FileInspection.Mod mod = inspection.metadata();
			if (mod == null) return;
			IDs = mod.IDs();
			version = mod.version();
			deps = mod.deps();
			nestedMods = mod.nestedMods().stream().map(nested -> new ModRecord(nested.hash(), new FileInspection.Inspection(true, nested, Set.of()),
					nested.path() == null ? null : LogicalPath.normalize(nested.path().toString()))).collect(Collectors.toSet());
			id = mod.id();
			environment = mod.environment().name();
		}

		private FileInspection.Inspection at(Path path) {
			Set<String> shipped = services == null ? Set.of() : services;
			if (id == null) return new FileInspection.Inspection(isMod, null, shipped);
			Set<String> modIds = IDs == null ? Set.of() : IDs;
			Set<String> modDeps = deps == null ? Set.of() : deps;
			Set<FileInspection.Mod> nested = nestedMods == null
					? Set.of()
					: nestedMods.stream().map(record -> record.at(record.entryPath == null ? null : Path.of(record.entryPath)).metadata()).collect(Collectors.toSet());
			return new FileInspection.Inspection(true, new FileInspection.Mod(modIds, hash, version, path, modDeps, nested, id, environmentOf(environment), clientPresenceOf(clientPresence)), shipped);
		}
	}

	private static ModEnvironment environmentOf(String name) {
		for (ModEnvironment environment : ModEnvironment.values()) if (environment.name().equals(name)) return environment;
		return null;
	}

	private static ClientPresence clientPresenceOf(String name) {
		for (ClientPresence presence : ClientPresence.values()) if (presence.name().equals(name)) return presence;
		return null;
	}
}
