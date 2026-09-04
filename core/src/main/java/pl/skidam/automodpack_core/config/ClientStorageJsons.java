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
		public String contentToken = "";
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
		public String contentToken = "";
		public String status = "ACTIVE";
		public GenerationJsons.OwnershipLedgerFields ownershipLedger = new GenerationJsons.OwnershipLedgerFields();
		/** Local sovereignty: while true nothing syncs the pack until the player attaches or the head catches up. */
		public boolean detached = false;
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
			public String contentToken = "";
			public String reason = "";
			public String preservedAt = "";
		}
	}

	public static class ClientOverlayFields {
		public String modpackId = "";
		public List<String> deletedPaths = List.of();
	}

	/** The informational boundary marker of one pack's last manual history compaction; never a correctness input. */
	public static class ClientCompactionReceiptFields {
		public String modpackId = "";
		/** The mirror's newest generation seq at compaction time; generations it doesn't keep are no longer locally restorable. */
		public long boundarySeq = -1;
		public String compactedAt = "";
		/** The compaction pass's reclaimed objects; the object store is shared, so this is the pass total, not a per-pack attribution. */
		public long reclaimedObjectCount = 0;
		public long reclaimedObjectBytes = 0;
	}

	public static class OfflineRepairJournalFields {
		public int schemaVersion = 1;
		public String modpackId = "";
		public String contentToken = "";
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
