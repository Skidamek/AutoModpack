package pl.skidam.automodpack_core.modpack.generation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;

/**
 * The compact, client-visible projection of a server generation lineage.
 *
 * <p>
 * The index deliberately contains no catalogue or ownership state. Those
 * details remain content-addressed server objects and can be requested only
 * for a selected entry. This lets a client understand skipped generations
 * without putting every historical manifest in every login response.
 * </p>
 */
public record GenerationHistoryIndex(
		String modpackId,
		String currentGenerationId,
		String compactionBoundaryGenerationId,
		List<Entry> entries) {
	public static final String CATALOGUE_REQUEST_PREFIX = "catalogue/";

	public GenerationHistoryIndex {
		modpackId = ModpackId.requireValid(modpackId);
		currentGenerationId = GenerationMetadata.requireDigest(currentGenerationId, "history current generation ID");
		compactionBoundaryGenerationId = GenerationMetadata.requireOptionalDigest(compactionBoundaryGenerationId, "history compaction boundary generation ID");
		entries = validateEntries(entries, modpackId, currentGenerationId, compactionBoundaryGenerationId);
	}

	public record Entry(
			String generationId,
			String parentGenerationId,
			Instant createdAt,
			String stateDigest,
			String rollbackTargetGenerationId,
			String patchNotes,
			String patchNotesDigest,
			GenerationDiff.Summary diffSummary,
			String diffDigest,
			boolean detailsAvailable,
			boolean rollbackAvailable) {
		public Entry {
			generationId = GenerationMetadata.requireDigest(generationId, "history generation ID");
			parentGenerationId = GenerationMetadata.requireOptionalDigest(parentGenerationId, "history parent generation ID");
			createdAt = Objects.requireNonNull(createdAt, "history createdAt");
			stateDigest = GenerationMetadata.requireDigest(stateDigest, "history state digest");
			rollbackTargetGenerationId = GenerationMetadata.requireOptionalDigest(rollbackTargetGenerationId, "history rollback target generation ID");
			patchNotes = GenerationMetadata.validateNotes(patchNotes);
			patchNotesDigest = GenerationMetadata.requireDigest(patchNotesDigest, "history patch notes digest");
			if (!GenerationIdentity.patchNotesDigest(patchNotes).equals(patchNotesDigest))
				throw new IllegalArgumentException("History patch notes digest does not match notes");
			diffSummary = validateSummary(diffSummary);
			diffDigest = GenerationMetadata.requireDigest(diffDigest, "history diff digest");
			if (!detailsAvailable && rollbackAvailable) throw new IllegalArgumentException("A history entry cannot be rollbackable without details");
		}

		public static Entry from(GenerationHistoryEntry entry, GenerationHistoryEntry parent) {
			Objects.requireNonNull(entry, "history entry");
			GenerationDiff diff = GenerationDiff.between(parent == null ? null : parent.manifest(), entry.manifest());
			GenerationMetadata metadata = entry.metadata();
			return new Entry(metadata.generationId(), metadata.parentGenerationId(), metadata.createdAt(), metadata.stateDigest(), metadata.rollbackTargetGenerationId(), metadata.patchNotes(),
					metadata.patchNotesDigest(), diff.summary(), GenerationHistoryIndex.diffDigest(parent == null ? "" : parent.metadata().stateDigest(), metadata.stateDigest(), diff.summary()), true, true);
		}

		public GenerationJsons.GenerationHistoryIndexEntryFields toFields() {
			GenerationJsons.GenerationHistoryIndexEntryFields fields = new GenerationJsons.GenerationHistoryIndexEntryFields();
			fields.generationId = generationId;
			fields.parentGenerationId = parentGenerationId;
			fields.createdAt = createdAt.toString();
			fields.stateDigest = stateDigest;
			fields.rollbackTargetGenerationId = rollbackTargetGenerationId;
			fields.patchNotes = patchNotes;
			fields.patchNotesDigest = patchNotesDigest;
			fields.addedFiles = diffSummary.addedFiles();
			fields.modifiedFiles = diffSummary.modifiedFiles();
			fields.removedFiles = diffSummary.removedFiles();
			fields.metadataOnlyFiles = diffSummary.metadataOnlyFiles();
			fields.metadataChanges = diffSummary.metadataChanges();
			fields.diffDigest = diffDigest;
			fields.detailsAvailable = detailsAvailable;
			fields.rollbackAvailable = rollbackAvailable;
			return fields;
		}

		public static Entry fromFields(GenerationJsons.GenerationHistoryIndexEntryFields fields) {
			if (fields == null) throw new IllegalArgumentException("Generation history index entry is missing");
			String createdAtText = Objects.requireNonNull(fields.createdAt, "Generation history index creation timestamp is missing");
			Instant createdAt;
			try {
				createdAt = Instant.parse(createdAtText);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException("Invalid generation history index creation timestamp", e);
			}
			if (!createdAt.toString().equals(createdAtText)) throw new IllegalArgumentException("Generation history index timestamp is not canonical");
			return new Entry(fields.generationId, fields.parentGenerationId, createdAt, fields.stateDigest, fields.rollbackTargetGenerationId, fields.patchNotes, fields.patchNotesDigest,
					new GenerationDiff.Summary(fields.addedFiles, fields.modifiedFiles, fields.removedFiles, fields.metadataOnlyFiles, fields.metadataChanges),
					fields.diffDigest, fields.detailsAvailable, fields.rollbackAvailable);
		}
	}

	public static GenerationHistoryIndex fromHistory(String modpackId, List<GenerationHistoryEntry> history) {
		Objects.requireNonNull(history, "generation history");
		if (history.isEmpty()) throw new IllegalArgumentException("Generation history is empty");
		List<Entry> entries = new ArrayList<>(history.size());
		GenerationHistoryEntry parent = null;
		for (GenerationHistoryEntry entry : history) {
			entries.add(Entry.from(entry, parent));
			parent = entry;
		}
		return new GenerationHistoryIndex(modpackId, entries.get(entries.size() - 1).generationId(), "", entries);
	}

	public GenerationJsons.GenerationHistoryIndexFields toFields() {
		GenerationJsons.GenerationHistoryIndexFields fields = new GenerationJsons.GenerationHistoryIndexFields();
		fields.modpackId = modpackId;
		fields.currentGenerationId = currentGenerationId;
		fields.compactionBoundaryGenerationId = compactionBoundaryGenerationId;
		fields.entries = entries.stream().map(Entry::toFields).toList();
		return fields;
	}

	public static GenerationHistoryIndex fromFields(GenerationJsons.GenerationHistoryIndexFields fields) {
		if (fields == null) throw new IllegalArgumentException("Generation history index is missing");
		if (fields.entries == null || fields.entries.isEmpty()) throw new IllegalArgumentException("Generation history index entries are missing");
		return new GenerationHistoryIndex(fields.modpackId, fields.currentGenerationId, fields.compactionBoundaryGenerationId,
				fields.entries.stream().map(Entry::fromFields).toList());
	}

	/** Appends the compact tail after the last indexed entry. */
	public GenerationHistoryIndex append(List<GenerationHistoryEntry> tail) {
		Objects.requireNonNull(tail, "history tail");
		if (tail.isEmpty()) return this;
		int start = 0;
		GenerationHistoryEntry previous = null;
		Entry last = entries.get(entries.size() - 1);
		GenerationHistoryEntry first = tail.get(0);
		if (last.generationId().equals(first.metadata().generationId())) {
			if (!last.parentGenerationId().equals(first.metadata().parentGenerationId()) || !last.stateDigest().equals(first.metadata().stateDigest()))
				throw new IllegalArgumentException("History index boundary does not match compact history");
			start = 1;
		} else {
			throw new IllegalArgumentException("History index tail must include its overlapping boundary entry");
		}
		List<Entry> combined = new ArrayList<>(entries);
		GenerationHistoryEntry prior = first;
		for (int index = start; index < tail.size(); index++) {
			GenerationHistoryEntry entry = tail.get(index);
			combined.add(Entry.from(entry, prior));
			prior = entry;
		}
		String current = combined.get(combined.size() - 1).generationId();
		return new GenerationHistoryIndex(modpackId, current, compactionBoundaryGenerationId, combined);
	}

	/** Marks detailed state before the selected boundary as unavailable while retaining its thin timeline. */
	public GenerationHistoryIndex compactBefore(String boundaryGenerationId) {
		GenerationMetadata.requireDigest(boundaryGenerationId, "compaction boundary generation ID");
		int boundaryIndex = indexOf(boundaryGenerationId);
		if (boundaryIndex < 0) throw new IllegalArgumentException("Compaction boundary is not in the current generation lineage: " + boundaryGenerationId);
		Set<String> retainedStateDigests = new HashSet<>();
		for (int index = boundaryIndex; index < entries.size(); index++) retainedStateDigests.add(entries.get(index).stateDigest());
		List<Entry> compacted = new ArrayList<>(boundaryIndex + 1);
		for (int index = 0; index <= boundaryIndex; index++) {
			Entry entry = entries.get(index);
			boolean boundary = index == boundaryIndex;
			boolean details = boundary || retainedStateDigests.contains(entry.stateDigest());
			compacted.add(new Entry(entry.generationId(), entry.parentGenerationId(), entry.createdAt(), entry.stateDigest(), entry.rollbackTargetGenerationId(), entry.patchNotes(), entry.patchNotesDigest(),
					entry.diffSummary(), entry.diffDigest(), details, boundary));
		}
		return new GenerationHistoryIndex(modpackId, boundaryGenerationId, boundaryGenerationId, compacted);
	}

	public Optional<Entry> find(String generationId) {
		if (generationId == null) return Optional.empty();
		return entries.stream().filter(entry -> entry.generationId().equals(generationId)).findFirst();
	}

	public static String catalogueRequestKey(String stateDigest) {
		return CATALOGUE_REQUEST_PREFIX + GenerationMetadata.requireDigest(stateDigest, "catalogue state digest");
	}

	private int indexOf(String generationId) {
		for (int index = 0; index < entries.size(); index++) if (entries.get(index).generationId().equals(generationId)) return index;
		return -1;
	}

	private static List<Entry> validateEntries(List<Entry> values, String modpackId, String currentGenerationId, String boundaryGenerationId) {
		if (values == null || values.isEmpty()) throw new IllegalArgumentException("Generation history index entries are missing");
		List<Entry> result = List.copyOf(values);
		Set<String> visited = new HashSet<>();
		String expectedParent = GenerationMetadata.ROOT_PARENT;
		for (Entry entry : result) {
			if (entry == null || !visited.add(entry.generationId())) throw new IllegalArgumentException("Generation history index contains a duplicate or missing entry");
			if (!entry.parentGenerationId().equals(expectedParent)) throw new IllegalArgumentException("Generation history index parent chain is not ordered at: " + entry.generationId());
			expectedParent = entry.generationId();
		}
		if (!expectedParent.equals(currentGenerationId)) throw new IllegalArgumentException("Generation history index does not end at current generation");
		int boundary = boundaryGenerationId.isEmpty() ? 0 : result.stream().map(Entry::generationId).toList().indexOf(boundaryGenerationId);
		if (boundary < 0) throw new IllegalArgumentException("Generation history index compaction boundary is not in its lineage");
		for (int index = 0; index < result.size(); index++) {
			Entry entry = result.get(index);
			if (index < boundary && entry.rollbackAvailable()) throw new IllegalArgumentException("Generation history entries before the compaction boundary cannot remain rollbackable");
			if (index >= boundary && (!entry.detailsAvailable() || !entry.rollbackAvailable()))
				throw new IllegalArgumentException("Generation history index boundary and newer entries must retain details and rollback state");
		}
		return result;
	}

	private static GenerationDiff.Summary validateSummary(GenerationDiff.Summary summary) {
		if (summary == null || summary.addedFiles() < 0 || summary.modifiedFiles() < 0 || summary.removedFiles() < 0 || summary.metadataOnlyFiles() < 0 || summary.metadataChanges() < 0)
			throw new IllegalArgumentException("Generation history diff summary is invalid");
		return summary;
	}

	private static String diffDigest(String parentStateDigest, String stateDigest, GenerationDiff.Summary summary) {
		return GenerationIdentity.sha1Bytes(new CanonicalEncoder().string("automodpack-generation-diff-v1").string(parentStateDigest).string(stateDigest)
				.integer(summary.addedFiles()).integer(summary.modifiedFiles()).integer(summary.removedFiles()).integer(summary.metadataOnlyFiles()).integer(summary.metadataChanges()).bytes());
	}

}
