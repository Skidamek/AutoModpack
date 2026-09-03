package pl.skidam.automodpack_core.modpack.generation;

import static pl.skidam.automodpack_core.utils.HashUtils.isCanonicalSha1;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import pl.skidam.automodpack_core.config.GenerationJsons;

/** One immutable journal record: the content change a publish made and the policy it served. */
public record JournalEntry(long seq, String contentToken, String policySha1, Instant createdAt, String notes, long restoreOf, boolean snapshot,
		List<Change> changes) {
	public static final long NO_RESTORE = -1;

	public JournalEntry {
		if (seq < 1) throw new IllegalArgumentException("Journal sequence must be positive");
		if (!isCanonicalSha1(contentToken)) throw new IllegalArgumentException("Invalid content token");
		if (!isCanonicalSha1(policySha1)) throw new IllegalArgumentException("Invalid policy document hash");
		createdAt = Objects.requireNonNull(createdAt, "createdAt");
		notes = validateNotes(notes);
		if (restoreOf < JournalEntry.NO_RESTORE) throw new IllegalArgumentException("Invalid restore reference");
		changes = List.copyOf(changes);
		if (!snapshot && changes.isEmpty()) throw new IllegalArgumentException("A content entry must carry its changes");
		if (snapshot && seq != 1) throw new IllegalArgumentException("Only the first journal entry can be a snapshot");
	}

	public JournalEntry withSeq(long newSeq) {
		return new JournalEntry(newSeq, contentToken, policySha1, createdAt, notes, restoreOf, snapshot, changes);
	}

	/** The per-entry change list kept server-side; summaries ride the wire instead. */
	public List<Change> treeChanges() {
		return changes;
	}

	public Summary summary() {
		int added = 0;
		int changed = 0;
		int removed = 0;
		for (Change change : changes) {
			if (change.fromSha1() == null) added++;
			else if (change.toSha1() == null) removed++;
			else changed++;
		}
		return new Summary(added, changed, removed);
	}

	public GenerationJsons.JournalEntryFields toFields() {
		GenerationJsons.JournalEntryFields fields = new GenerationJsons.JournalEntryFields();
		fields.seq = seq;
		fields.contentToken = contentToken;
		fields.policySha1 = policySha1;
		fields.createdAt = createdAt.toString();
		fields.notes = notes;
		fields.restoreOf = restoreOf;
		fields.snapshot = snapshot;
		List<GenerationJsons.JournalChangeFields> changeFields = new ArrayList<>();
		for (Change change : changes) changeFields.add(change.toFields());
		fields.changes = changeFields;
		return fields;
	}

	public static JournalEntry fromFields(GenerationJsons.JournalEntryFields fields) {
		if (fields == null) throw new IllegalArgumentException("Journal entry is missing");
		if (fields.seq < 1) throw new IllegalArgumentException("Journal sequence must be positive");
		if (!isCanonicalSha1(fields.contentToken)) throw new IllegalArgumentException("Invalid content token in journal entry " + fields.seq);
		if (!isCanonicalSha1(fields.policySha1)) throw new IllegalArgumentException("Invalid policy document hash in journal entry " + fields.seq);
		Instant createdAt;
		try {
			createdAt = Instant.parse(fields.createdAt);
		} catch (DateTimeParseException | NullPointerException e) {
			throw new IllegalArgumentException("Invalid creation timestamp in journal entry " + fields.seq, e);
		}
		List<Change> changes = new ArrayList<>();
		for (GenerationJsons.JournalChangeFields change : fields.changes == null ? List.<GenerationJsons.JournalChangeFields>of() : fields.changes)
			changes.add(Change.fromFields(change));
		return new JournalEntry(fields.seq, fields.contentToken, fields.policySha1, createdAt, fields.notes == null ? "" : fields.notes, fields.restoreOf, fields.snapshot,
				changes);
	}

	public record Change(String path, String fromSha1, String toSha1, long toSize) {
		public Change {
			Objects.requireNonNull(path, "path");
			if (fromSha1 != null && !isCanonicalSha1(fromSha1)) throw new IllegalArgumentException("Invalid change source for " + path);
			if (toSha1 == null && toSize != 0) throw new IllegalArgumentException("A removed path cannot carry a size: " + path);
			if (toSha1 != null && !isCanonicalSha1(toSha1)) throw new IllegalArgumentException("Invalid change target for " + path);
		}

		public static Change added(String path, String toSha1, long toSize) {
			return new Change(path, null, toSha1, toSize);
		}

		public static Change removed(String path, String fromSha1) {
			return new Change(path, fromSha1, null, 0);
		}

		public GenerationJsons.JournalChangeFields toFields() {
			GenerationJsons.JournalChangeFields fields = new GenerationJsons.JournalChangeFields();
			fields.path = path;
			fields.fromSha1 = fromSha1;
			fields.toSha1 = toSha1;
			fields.toSize = toSize;
			return fields;
		}

		public static Change fromFields(GenerationJsons.JournalChangeFields fields) {
			if (fields == null || fields.path == null || fields.path.isBlank()) throw new IllegalArgumentException("Journal change is missing its path");
			String from = fields.fromSha1 == null || fields.fromSha1.isBlank() ? null : fields.fromSha1;
			String to = fields.toSha1 == null || fields.toSha1.isBlank() ? null : fields.toSha1;
			return new Change(fields.path, from, to, to == null ? 0 : fields.toSize);
		}
	}

	public record Summary(int added, int changed, int removed) {}

	private static String validateNotes(String notes) {
		Objects.requireNonNull(notes, "Patch notes are missing");
		String normalized = notes.replace("\r\n", "\n").replace('\r', '\n');
		try {
			StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
					.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(normalized));
		} catch (java.nio.charset.CharacterCodingException e) {
			throw new IllegalArgumentException("Patch notes are not valid UTF-8", e);
		}
		return normalized;
	}
}
