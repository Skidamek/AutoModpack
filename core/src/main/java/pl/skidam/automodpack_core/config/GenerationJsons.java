package pl.skidam.automodpack_core.config;

import java.util.List;
import java.util.Set;

@SuppressWarnings("unused")
public class GenerationJsons {

	/** One persisted journal line: the content change a publish made and the policy it served. */
	public static class JournalEntryFields {
		public long seq;
		public String contentToken = "";
		public String policySha1 = "";
		public String createdAt = "";
		public String notes = "";
		public long restoreOf = -1;
		public boolean snapshot;
		public List<JournalChangeFields> changes = List.of();
	}

	public static class JournalChangeFields {
		public String path = "";
		public String fromSha1 = "";
		public String toSha1 = "";
		public long toSize;
	}

	/** The head document served to clients: content identity, ledger, and the policy document. */
	public static class HeadDocumentFields {
		public String contentToken = "";
		public String policySha1 = "";
		public String createdAt = "";
		public long journalHead;
		public OwnershipLedgerFields ownershipLedger = new OwnershipLedgerFields();
		public ModpackJsons.CompleteModpackContentFields policy = new ModpackJsons.CompleteModpackContentFields();
	}

	public static class OwnershipLedgerFields {
		public String modpackId = "";
		public List<EntryFields> entries = List.of();
		public String digest = "";

		public static class EntryFields {
			public String logicalPath = "";
			public List<ContentFields> historicalHashes = List.of();
			public Set<String> historicalGroupIds = Set.of();
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
}
