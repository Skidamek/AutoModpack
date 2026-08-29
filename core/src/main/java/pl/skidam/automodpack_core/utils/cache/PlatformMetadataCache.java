package pl.skidam.automodpack_core.utils.cache;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.platforms.CurseForgeAPI;
import pl.skidam.automodpack_core.platforms.ModrinthAPI;

/** A shared sha1-keyed cache of resolved Modrinth and CurseForge lookups backed by immutable loose records. */
public class PlatformMetadataCache implements AutoCloseable {

	private static final SharedCacheRegistry<PlatformMetadataCache> REGISTRY = new SharedCacheRegistry<>();
	private static final String RECORD_SUFFIX = ".json";

	private final Path recordsDirectory;
	private final Map<String, Record> hotRecords = new ConcurrentHashMap<>();
	private final Object[] locks = new Object[64];

	public static PlatformMetadataCache open(Path path) throws IOException {
		return REGISTRY.acquire(path, PlatformMetadataCache::new);
	}

	private PlatformMetadataCache(Path recordsDirectory) {
		this.recordsDirectory = recordsDirectory;
		for (int i = 0; i < locks.length; i++) locks[i] = new Object();
	}

	/** Returns the cached records for the given sha1 digests, keyed by the requested digest and omitting the missing ones. */
	public Map<String, Record> getAll(Collection<String> sha1s) {
		Map<String, Record> records = new HashMap<>();
		for (String sha1 : sha1s) {
			if (sha1 == null || sha1.isBlank()) continue;
			String normalizedHash = sha1.toLowerCase(Locale.ROOT);
			synchronized (lock(normalizedHash)) {
				Record record = readRecord(normalizedHash);
				if (record != null) records.put(sha1, record);
			}
		}
		return records;
	}

	public void putModrinth(String sha1, ModrinthAPI info, String mainPageUrl) {
		if (sha1 == null || sha1.isBlank() || info == null || info.downloadUrl() == null) return;
		String normalizedHash = sha1.toLowerCase(Locale.ROOT);
		synchronized (lock(normalizedHash)) {
			Record record = readOrCreateRecord(normalizedHash);
			record.modrinth = new ModrinthEntry(info, mainPageUrl);
			writeRecord(normalizedHash, record);
		}
	}

	public void putCurseForge(String sha1, CurseForgeAPI info) {
		if (sha1 == null || sha1.isBlank() || info == null || info.downloadUrl() == null) return;
		String normalizedHash = sha1.toLowerCase(Locale.ROOT);
		synchronized (lock(normalizedHash)) {
			Record record = readOrCreateRecord(normalizedHash);
			record.curseforge = new CurseForgeEntry(info);
			writeRecord(normalizedHash, record);
		}
	}

	public void evict(String sha1) {
		if (sha1 == null || sha1.isBlank()) return;
		String normalizedHash = sha1.toLowerCase(Locale.ROOT);
		synchronized (lock(normalizedHash)) {
			hotRecords.remove(normalizedHash);
			try {
				Files.deleteIfExists(recordPath(normalizedHash));
			} catch (IOException e) {
				LOGGER.debug("Could not evict platform metadata cache record: {}", normalizedHash, e);
			}
		}
	}

	private Object lock(String normalizedHash) {
		return locks[Math.floorMod(normalizedHash.hashCode(), locks.length)];
	}

	private Record readOrCreateRecord(String normalizedHash) {
		Record record = readRecord(normalizedHash);
		return record != null ? record : new Record(normalizedHash);
	}

	private Record readRecord(String normalizedHash) {
		Record hot = hotRecords.get(normalizedHash);
		if (hot != null) return hot;
		Path recordPath = recordPath(normalizedHash);
		if (!Files.isRegularFile(recordPath, LinkOption.NOFOLLOW_LINKS)) return null;
		try {
			Record record = ConfigTools.read(recordPath, Record.class).orElse(null);
			if (record == null || !normalizedHash.equals(record.sha1)) return null;
			hotRecords.put(normalizedHash, record);
			return record;
		} catch (RuntimeException e) {
			LOGGER.debug("Ignoring invalid platform metadata cache record: {}", recordPath);
			return null;
		}
	}

	private void writeRecord(String normalizedHash, Record record) {
		hotRecords.put(normalizedHash, record);
		try {
			ConfigTools.writeAtomic(recordPath(normalizedHash), record);
		} catch (IOException e) {
			LOGGER.debug("Could not persist platform metadata cache record: {}", normalizedHash, e);
		}
	}

	private Path recordPath(String hash) {
		return recordsDirectory.resolve(hash.substring(0, 2)).resolve(hash.substring(2) + RECORD_SUFFIX);
	}

	@Override
	public void close() {
		if (REGISTRY.release(recordsDirectory, this)) hotRecords.clear();
	}

	public static final class Record {
		private String sha1;
		private ModrinthEntry modrinth;
		private CurseForgeEntry curseforge;

		private Record() {}

		private Record(String sha1) {
			this.sha1 = sha1;
		}

		public ModrinthEntry modrinth() {
			return modrinth;
		}

		public CurseForgeEntry curseforge() {
			return curseforge;
		}
	}

	public static final class ModrinthEntry {
		private String modrinthId;
		private String downloadUrl;
		private String fileVersion;
		private String fileName;
		private long fileSize;
		private String releaseType;
		private String mainPageUrl;

		private ModrinthEntry() {}

		private ModrinthEntry(ModrinthAPI info, String mainPageUrl) {
			modrinthId = info.modrinthID();
			downloadUrl = info.downloadUrl();
			fileVersion = info.fileVersion();
			fileName = info.fileName();
			fileSize = info.fileSize();
			releaseType = info.releaseType();
			this.mainPageUrl = mainPageUrl;
		}

		public String modrinthId() {
			return modrinthId;
		}

		public String downloadUrl() {
			return downloadUrl;
		}

		public String fileVersion() {
			return fileVersion;
		}

		public String fileName() {
			return fileName;
		}

		public long fileSize() {
			return fileSize;
		}

		public String releaseType() {
			return releaseType;
		}

		public String mainPageUrl() {
			return mainPageUrl;
		}
	}

	public static final class CurseForgeEntry {
		private int modId;
		private String downloadUrl;
		private String fileVersion;
		private String fileName;
		private String fileSize;
		private String releaseType;
		private String projectPageUrl;

		private CurseForgeEntry() {}

		private CurseForgeEntry(CurseForgeAPI info) {
			modId = info.modId();
			downloadUrl = info.downloadUrl();
			fileVersion = info.fileVersion();
			fileName = info.fileName();
			fileSize = info.fileSize();
			releaseType = info.releaseType();
			projectPageUrl = info.projectPageUrl();
		}

		public int modId() {
			return modId;
		}

		public String downloadUrl() {
			return downloadUrl;
		}

		public String fileVersion() {
			return fileVersion;
		}

		public String fileName() {
			return fileName;
		}

		public String fileSize() {
			return fileSize;
		}

		public String releaseType() {
			return releaseType;
		}

		public String projectPageUrl() {
			return projectPageUrl;
		}
	}
}
