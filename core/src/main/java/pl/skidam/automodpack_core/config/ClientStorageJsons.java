package pl.skidam.automodpack_core.config;

import java.util.List;
import java.util.Set;

public class ClientStorageJsons {

	public static class ClientBaselineFields {
		public int schemaVersion = 1;
		public String modpackId = "";
		public List<EntryFields> entries = List.of();

		public static class EntryFields {
			public String logicalPath = "";
			public String objectHash = "";
			public long size = -1;
			public boolean absent;
			public String baselineGenerationId = "";
		}
	}

	public static class ClientGeneratedCopiesFields {
		public int schemaVersion = 1;
		public String modpackId = "";
		public String generationId = "";
		public String selectionDigest = "";
		public List<EntryFields> entries = List.of();

		public static class EntryFields {
			public String logicalPath = "";
			public String sha1 = "";
			public long size = -1;
		}
	}

	public static class ClientGenerationStateFields {
		public String modpackId = "";
		public String generationId = "";
		public String status = "ACTIVE";
	}

	public static class ClientRecoveryArchiveFields {
		public int schemaVersion = 1;
		public List<EntryFields> entries = List.of();

		public static class EntryFields {
			public String logicalPath = "";
			public String sha1 = "";
			public long size = -1;
			public String sourceGenerationId = "";
			public String preservedAt = "";
		}
	}

	public static class ClientQuarantineFields {
		public int schemaVersion = 1;
		public String modpackId = "";
		public List<EntryFields> entries = List.of();

		public static class EntryFields {
			public String conflictId = "";
			public String action = "";
			public Set<String> modIds = Set.of();
			public String sourcePath = "";
			public String sourceHash = "";
			public long sourceSize = -1;
			public String targetPath = "";
			public String targetHash = "";
			public long targetSize = -1;
			public String quarantinePath = "";
			public String sourceGenerationId = "";
			public String quarantinedAt = "";
		}
	}

	public static class ClientOverlayFields {
		public String modpackId = "";
		public List<String> deletedPaths = List.of();
	}
}
