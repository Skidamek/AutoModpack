package pl.skidam.automodpack_core.utils.cache;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import pl.skidam.automodpack_core.platforms.CurseForgeAPI;
import pl.skidam.automodpack_core.platforms.ModrinthAPI;

/** A shared sha1-keyed cache of resolved Modrinth and CurseForge lookups backed by immutable loose records. */
public class PlatformMetadataCache extends LooseRecordCache<PlatformMetadataCache.Record> {

	private static final SharedCacheRegistry<PlatformMetadataCache> REGISTRY = new SharedCacheRegistry<>();

	public static PlatformMetadataCache open(Path path) throws IOException {
		return REGISTRY.acquire(path, PlatformMetadataCache::new);
	}

	private PlatformMetadataCache(Path recordsDirectory) {
		super(recordsDirectory, "platform metadata");
	}

	/** Returns the cached records for the given sha1 digests, keyed by the requested digest and omitting the missing ones. */
	public Map<String, Record> getAll(Collection<String> sha1s) {
		Map<String, Record> records = new HashMap<>();
		for (String sha1 : sha1s) {
			if (sha1 == null || sha1.isBlank()) continue;
			String normalizedHash = sha1.toLowerCase(Locale.ROOT);
			synchronized (lock(normalizedHash)) {
				Record record = readRecord(normalizedHash, Record.class);
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

	private Record readOrCreateRecord(String normalizedHash) {
		Record record = readRecord(normalizedHash, Record.class);
		return record != null ? record : new Record(normalizedHash);
	}

	@Override
	protected boolean validate(Record record, String key) {
		return key.equals(record.sha1);
	}

	@Override
	protected boolean releaseFromRegistry() {
		return REGISTRY.release(recordsDirectory, this);
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
