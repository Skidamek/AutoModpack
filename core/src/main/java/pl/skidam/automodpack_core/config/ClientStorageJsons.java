package pl.skidam.automodpack_core.config;

import java.util.List;

public class ClientStorageJsons {
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
		/**
		 * Local sovereignty of the active pack. Exactly two transition kinds exist, both explicit: declared true by
		 * declining a reviewed update, rolling back to an older generation, or the details screen's stop-syncing
		 * action; cleared false only by an attaching sync (the updater's commit, or its nothing-to-apply exit).
		 * Head equality alone never moves the flag.
		 */
		public boolean detached = false;
	}

	/** One line of the instance timeline journal: parent, tree hash, and event. The tree document is a sibling file. */
	public static class SnapshotFields {
		public long seq = -1;
		public long parentSeq = 0;
		public String treeSha1 = "";
		public String kind = "";
		public String modpackId = "";
		public String transactionId = "";
		public String createdAt = "";
	}

	/** One instance tree document: live identity plus every tracked file at that moment. */
	public static class InstanceTreeFields {
		public String activeModpackId = "";
		public String contentToken = "";
		public boolean detached;
		public List<String> requestedGroups = List.of();
		public List<String> requestedCategories = List.of();
		public List<String> excludedGroups = List.of();
		public List<TombstoneFields> tombstones = List.of();
		public List<FileFields> files = List.of();

		public static class TombstoneFields {
			public String modpackId = "";
			public List<String> deletedPaths = List.of();
		}

		public static class FileFields {
			public String root = "";
			public String overlayPackId = "";
			public String path = "";
			public String sha1 = "";
			public long size = -1;
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
