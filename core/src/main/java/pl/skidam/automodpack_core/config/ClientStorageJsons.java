package pl.skidam.automodpack_core.config;

import java.util.List;

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

	public static class ClientPreservationVaultFields {
		public int schemaVersion = 1;
		public String modpackId = "";
		public List<ClaimFields> claims = List.of();

		public static class ClaimFields {
			public String claimId = "";
			public String originalPath = "";
			public String sourceRoot = "";
			public String objectHash = "";
			public long size = -1;
			public String modpackId = "";
			public String generationId = "";
			public String reason = "";
			public String preservedAt = "";
			public String status = "";
		}
	}

	public static class ClientOverlayFields {
		public String modpackId = "";
		public List<String> deletedPaths = List.of();
	}

	public static class OfflineRepairJournalFields {
		public int schemaVersion = 1;
		public String modpackId = "";
		public String generationId = "";
		public String selectionDigest = "";
		public List<EditableResetFields> editableResets = List.of();
		public List<UnownedModFields> unownedMods = List.of();

		public static class EditableResetFields {
			public String logicalPath = "";
			public String defaultHash = "";
			public long defaultSize = -1;
			public String currentHash;
			public long currentSize = -1;
			public boolean absent;
		}

		public static class UnownedModFields {
			public String logicalPath = "";
			public String objectHash = "";
			public long size = -1;
		}
	}
}
