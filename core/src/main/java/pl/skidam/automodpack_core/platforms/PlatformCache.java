package pl.skidam.automodpack_core.platforms;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import pl.skidam.automodpack_core.utils.cache.LooseRecordCache;
import pl.skidam.automodpack_core.utils.cache.SharedCacheRegistry;

/** A shared sha1-keyed cache of resolved Modrinth and CurseForge lookups backed by immutable loose records. */
public class PlatformCache extends LooseRecordCache<PlatformCache.Record> {

	private static final SharedCacheRegistry<PlatformCache> REGISTRY = new SharedCacheRegistry<>();

	public static PlatformCache open(Path path) throws IOException {
		return REGISTRY.acquire(path, PlatformCache::new);
	}

	private PlatformCache(Path recordsDirectory) {
		super(recordsDirectory, "platform cache");
	}

	/** The cached records for the given sha1 digests, keyed by the normalized digest they were requested under, omitting the missing ones. */
	public Map<String, Record> getAll(Collection<String> sha1s) {
		Map<String, Record> records = new HashMap<>();
		for (String sha1 : sha1s) {
			if (sha1 == null || sha1.isBlank()) continue;
			String normalizedHash = sha1.toLowerCase(Locale.ROOT);
			synchronized (lock(normalizedHash)) {
				Record record = sanitize(readRecord(normalizedHash, Record.class));
				if (record != null && (record.modrinth() != null || record.curseforge() != null)) records.put(normalizedHash, record);
			}
		}
		return records;
	}

	public void putModrinth(String sha1, ModrinthAPI info, String mainPageUrl) {
		if (sha1 == null || sha1.isBlank() || info == null || info.downloadUrl() == null) return;
		String normalizedHash = sha1.toLowerCase(Locale.ROOT);
		synchronized (lock(normalizedHash)) {
			Record record = readOrCreateRecord(normalizedHash);
			String page = mainPageUrl != null ? mainPageUrl : record.modrinth == null ? null : record.modrinth.mainPageUrl;
			record.modrinth = new ModrinthEntry(info, page);
			writeRecord(normalizedHash, record);
		}
	}

	public void putCurseForge(String sha1, CurseForgeAPI info) {
		if (sha1 == null || sha1.isBlank() || info == null) return;
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
				LOGGER.debug("Could not evict platform cache record: {}", normalizedHash, e);
			}
		}
	}

	private Record readOrCreateRecord(String normalizedHash) {
		Record record = sanitize(readRecord(normalizedHash, Record.class));
		return record != null ? record : new Record(normalizedHash);
	}

	/** An unlisted CurseForge project has no usable public page, which says nothing about what else the entry knows: only the page goes, the side tags stay. */
	private Record sanitize(Record record) {
		if (record != null && record.curseforge != null && CurseForgeAPI.isPlaceholderProjectPage(record.curseforge.projectPageUrl)) record.curseforge.projectPageUrl = null;
		return record;
	}

	@Override
	protected boolean belongsTo(Record record, String key) {
		return key.equals(record.sha1);
	}

	@Override
	protected boolean releaseFromRegistry() {
		return REGISTRY.release(recordsDirectory, this);
	}

	public static final class Record {
		/** The Modrinth version environments that mean clients never load the file. */
		private static final Set<String> CLIENT_UNSUPPORTED_MODRINTH = Set.of("server_only", "dedicated_server_only");

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

		/** Why a client does not run this file, or null to keep it in the pack: the Modrinth version environment decides whenever it is filled, the CurseForge side tags only fill a missing field. */
		public String clientExclusionReason() {
			String environment = modrinth == null ? null : modrinth.environment;
			if (environment != null && !environment.isBlank()) {
				String declared = environment.trim().toLowerCase(Locale.ROOT);
				return CLIENT_UNSUPPORTED_MODRINTH.contains(declared) ? "modrinth environment " + declared : null;
			}
			if (curseforge == null || !curseforge.sideTagsKnown) return null;
			return curseforge.serverTag && !curseforge.clientTag ? "curseforge gameVersions Server" : null;
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
		private String environment;

		private ModrinthEntry() {}

		private ModrinthEntry(ModrinthAPI info, String mainPageUrl) {
			modrinthId = info.modrinthID();
			downloadUrl = info.downloadUrl();
			fileVersion = info.fileVersion();
			fileName = info.fileName();
			fileSize = info.fileSize();
			releaseType = info.releaseType();
			this.mainPageUrl = mainPageUrl;
			environment = info.environment();
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

		public String environment() {
			return environment;
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
		private boolean clientTag;
		private boolean serverTag;
		/** Written by every entry, so an older record that predates the field reads as never told and is asked again. */
		private boolean sideTagsKnown;

		private CurseForgeEntry() {}

		private CurseForgeEntry(CurseForgeAPI info) {
			modId = info.modId();
			downloadUrl = info.downloadUrl();
			fileVersion = info.fileVersion();
			fileName = info.fileName();
			fileSize = info.fileSize();
			releaseType = info.releaseType();
			projectPageUrl = info.projectPageUrl();
			clientTag = info.clientTag();
			serverTag = info.serverTag();
			sideTagsKnown = true;
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

		public boolean clientTag() {
			return clientTag;
		}

		public boolean serverTag() {
			return serverTag;
		}

		public boolean sideTagsKnown() {
			return sideTagsKnown;
		}
	}
}
