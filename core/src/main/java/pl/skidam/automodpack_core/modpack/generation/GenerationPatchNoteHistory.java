package pl.skidam.automodpack_core.modpack.generation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import pl.skidam.automodpack_core.config.ModpackJsons;

/** The ordered, compact patch-note projection carried with a current generation target. */
public final class GenerationPatchNoteHistory {
	private GenerationPatchNoteHistory() {}

	public record Entry(int schemaVersion, String generationId, String parentGenerationId, Instant createdAt, String patchNotes, String patchNotesDigest) {
		public Entry {
			if (schemaVersion != GenerationMetadata.CURRENT_SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported patch-note history schema version: " + schemaVersion);
			generationId = GenerationMetadata.requireDigest(generationId, "generation ID");
			parentGenerationId = GenerationMetadata.requireOptionalDigest(parentGenerationId, "parent generation ID");
			createdAt = Objects.requireNonNull(createdAt, "createdAt");
			patchNotes = GenerationMetadata.validateNotes(patchNotes);
			patchNotesDigest = GenerationMetadata.requireDigest(patchNotesDigest, "patch notes digest");
			if (!GenerationIdentity.patchNotesDigest(patchNotes).equals(patchNotesDigest)) throw new IllegalArgumentException("Patch-note history digest does not match its notes");
		}

		public static Entry fromMetadata(GenerationMetadata metadata) {
			Objects.requireNonNull(metadata, "generation metadata");
			return new Entry(metadata.schemaVersion(), metadata.generationId(), metadata.parentGenerationId(), metadata.createdAt(), metadata.patchNotes(), metadata.patchNotesDigest());
		}

		public ModpackJsons.CompleteModpackContentFields.PatchNotesHistoryEntryFields toFields() {
			ModpackJsons.CompleteModpackContentFields.PatchNotesHistoryEntryFields fields = new ModpackJsons.CompleteModpackContentFields.PatchNotesHistoryEntryFields();
			fields.schemaVersion = schemaVersion;
			fields.generationId = generationId;
			fields.parentGenerationId = parentGenerationId;
			fields.createdAt = createdAt.toString();
			fields.patchNotes = patchNotes;
			fields.patchNotesDigest = patchNotesDigest;
			return fields;
		}

		public static Entry fromFields(ModpackJsons.CompleteModpackContentFields.PatchNotesHistoryEntryFields fields) {
			if (fields == null) throw new IllegalArgumentException("Patch-note history entry is missing");
			String createdAtText = Objects.requireNonNull(fields.createdAt, "Patch-note history creation timestamp is missing");
			Instant createdAt;
			try {
				createdAt = Instant.parse(createdAtText);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException("Invalid patch-note history creation timestamp", e);
			}
			if (!createdAt.toString().equals(createdAtText)) throw new IllegalArgumentException("Patch-note history creation timestamp is not canonical");
			return new Entry(fields.schemaVersion, fields.generationId, fields.parentGenerationId, createdAt, fields.patchNotes, fields.patchNotesDigest);
		}
	}

	public static List<Entry> fromHistory(List<GenerationHistoryEntry> history) {
		Objects.requireNonNull(history, "generation history");
		if (history.isEmpty()) return List.of();
		List<Entry> entries = new ArrayList<>(history.size());
		for (GenerationHistoryEntry entry : history) entries.add(Entry.fromMetadata(entry.metadata()));
		return validate(entries, entries.isEmpty() ? null : entries.get(entries.size() - 1).generationId());
	}

	static List<Entry> validateForGeneration(List<Entry> history, String currentGenerationId) {
		return validate(history, currentGenerationId);
	}

	public static List<Entry> fromRecords(List<GenerationRecord> records) {
		Objects.requireNonNull(records, "generation records");
		if (records.isEmpty()) return List.of();
		List<Entry> entries = new ArrayList<>(records.size());
		for (GenerationRecord record : records) entries.add(Entry.fromMetadata(record.metadata()));
		return validate(entries, entries.isEmpty() ? null : entries.get(entries.size() - 1).generationId());
	}

	public static List<Entry> fromFields(ModpackJsons.CompleteModpackContentFields fields) {
		Objects.requireNonNull(fields, "complete generation fields");
		Entry current = Entry.fromMetadata(GenerationMetadata.fromFields(fields.generation));
		if (fields.patchNotesHistory == null || fields.patchNotesHistory.isEmpty()) return List.of(current);
		List<Entry> entries = new ArrayList<>(fields.patchNotesHistory.size());
		for (ModpackJsons.CompleteModpackContentFields.PatchNotesHistoryEntryFields entry : fields.patchNotesHistory) entries.add(Entry.fromFields(entry));
		if (isPartialCurrentEntry(entries, current)) return List.of(current);
		List<Entry> validated = validate(entries, current.generationId());
		if (!validated.get(validated.size() - 1).equals(current)) throw new IllegalArgumentException("Patch-note history current entry does not match generation metadata");
		return validated;
	}

	public static void writeFields(ModpackJsons.CompleteModpackContentFields fields, List<Entry> history) {
		Objects.requireNonNull(fields, "complete generation fields");
		Entry current = Entry.fromMetadata(GenerationMetadata.fromFields(fields.generation));
		if (history != null && history.size() == 1 && current.equals(history.get(0)) && !current.parentGenerationId().isEmpty()) {
			fields.patchNotesHistory = List.of();
			return;
		}
		List<Entry> validated = validate(history, current.generationId());
		fields.patchNotesHistory = validated.stream().map(Entry::toFields).toList();
	}

	public static List<Entry> forRecord(GenerationRecord record) {
		return List.of(Entry.fromMetadata(Objects.requireNonNull(record, "generation record").metadata()));
	}

	public static List<Entry> after(List<Entry> history, String installedGenerationId) {
		if (history != null && history.size() == 1 && history.get(0) != null && !history.get(0).parentGenerationId().isEmpty()) {
			return installedGenerationId != null && installedGenerationId.equals(history.get(0).generationId()) ? List.of() : List.copyOf(history);
		}
		List<Entry> validated = validate(history, null);
		if (installedGenerationId == null || installedGenerationId.isBlank()) return validated;
		for (int index = 0; index < validated.size(); index++) {
			if (validated.get(index).generationId().equals(installedGenerationId)) return List.copyOf(validated.subList(index + 1, validated.size()));
		}
		return validated;
	}

	public static boolean containsNotes(List<Entry> history) {
		for (Entry entry : history) if (!entry.patchNotes().isBlank()) return true;
		return false;
	}

	/** Returns the newest non-empty note available at or before the end of this history. */
	public static String latestNotes(List<Entry> history) {
		Objects.requireNonNull(history, "patch notes history");
		for (int index = history.size() - 1; index >= 0; index--) {
			String notes = history.get(index).patchNotes();
			if (!notes.isBlank()) return notes;
		}
		return "";
	}

	private static List<Entry> validate(List<Entry> history, String currentGenerationId) {
		if (history == null || history.isEmpty()) throw new IllegalArgumentException("Patch-note history is empty");
		Set<String> visited = new HashSet<>();
		String expectedParent = GenerationMetadata.ROOT_PARENT;
		for (Entry entry : history) {
			if (entry == null) throw new IllegalArgumentException("Patch-note history contains a missing entry");
			if (!visited.add(entry.generationId())) throw new IllegalArgumentException("Patch-note history contains a duplicate generation");
			if (!entry.parentGenerationId().equals(expectedParent)) throw new IllegalArgumentException("Patch-note history parent chain is not ordered at: " + entry.generationId());
			expectedParent = entry.generationId();
		}
		if (currentGenerationId != null && !expectedParent.equals(currentGenerationId)) throw new IllegalArgumentException("Patch-note history does not end at the current generation");
		return List.copyOf(history);
	}

	private static boolean isPartialCurrentEntry(List<Entry> history, Entry current) {
		return history.size() == 1 && current.equals(history.get(0)) && !current.parentGenerationId().isEmpty();
	}

}
