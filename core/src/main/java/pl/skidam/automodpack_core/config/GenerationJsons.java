package pl.skidam.automodpack_core.config;

import java.util.List;
import java.util.Set;

@SuppressWarnings("unused")
public class GenerationJsons {

	public static class GenerationPointerFields {
		public int schemaVersion;
		public String generationId = "";
	}

	public static class GenerationCheckpointFields {
		public int schemaVersion;
		public String boundaryGenerationId = "";
		public ModpackJsons.CompleteModpackContentFields record = new ModpackJsons.CompleteModpackContentFields();
		public List<ModpackJsons.CompleteModpackContentFields.PatchNotesHistoryEntryFields> patchNotesHistory = List.of();
		public List<String> supersededGenerationIds = List.of();
		public List<String> supersededCatalogueStateDigests = List.of();
	}

	public static class OwnershipLedgerFields {
		public String modpackId = "";
		public List<EntryFields> entries = List.of();
		public String digest = "";

		public static class EntryFields {
			public String logicalPath = "";
			public List<ContentFields> historicalHashes = List.of();
			public Set<String> historicalGroupIds = Set.of();
			public String firstPublishedGenerationId = "";
			public String lastPublishedGenerationId = "";
			public String currentStatus = "";
		}

		public static class ContentFields {
			public String sha1 = "";
			public long size;

			public ContentFields() {}

			public ContentFields(String sha1, long size) {
				this.sha1 = sha1;
				this.size = size;
			}
		}
	}

	public static class OwnershipDeltaFields {
		public String modpackId = "";
		public List<ChangeFields> changes = List.of();
		public String digest = "";

		public static class ChangeFields {
			public String logicalPath = "";
			public String kind = "";
			public OwnershipLedgerFields.ContentFields content;
			public List<OwnershipLedgerFields.ContentFields> contents = List.of();
			public Set<String> groupIds = Set.of();
		}
	}

	public static class CatalogueSnapshotFields {
		public String stateDigest = "";
		public ModpackJsons.CompleteModpackContentFields catalogue = new ModpackJsons.CompleteModpackContentFields();
	}

	public static class GenerationCommitFields {
		public int schemaVersion;
		public String generationId = "";
		public String parentGenerationId = "";
		public String modpackId = "";
		public String createdAt = "";
		public String stateDigest = "";
		public String ledgerDigest = "";
		public String ownershipDeltaDigest = "";
		public String patchNotes = "";
		public String patchNotesDigest = "";
		public String rollbackTargetGenerationId = "";
	}
}
