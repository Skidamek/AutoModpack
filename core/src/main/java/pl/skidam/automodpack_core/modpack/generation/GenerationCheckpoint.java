package pl.skidam.automodpack_core.modpack.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.config.GenerationJsons;

/** Durable complete generation state at the intentional boundary of compacted history. */
public record GenerationCheckpoint(int schemaVersion, String boundaryGenerationId, GenerationRecord record,
		List<GenerationPatchNoteHistory.Entry> patchNotesHistory, Set<String> supersededGenerationIds, Set<String> supersededCatalogueStateDigests) {
	public static final int CURRENT_SCHEMA_VERSION = 1;

	public GenerationCheckpoint {
		if (schemaVersion != CURRENT_SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported generation checkpoint schema version: " + schemaVersion);
		boundaryGenerationId = GenerationMetadata.requireDigest(boundaryGenerationId, "checkpoint boundary generation ID");
		record = Objects.requireNonNull(record, "checkpoint generation record");
		if (!boundaryGenerationId.equals(record.metadata().generationId())) throw new IllegalArgumentException("Checkpoint boundary does not match its generation record");
		patchNotesHistory = GenerationPatchNoteHistory.validateForGeneration(patchNotesHistory, boundaryGenerationId);
		supersededGenerationIds = immutableDigests(supersededGenerationIds, "superseded generation ID");
		supersededCatalogueStateDigests = immutableDigests(supersededCatalogueStateDigests, "superseded catalogue state digest");
	}

	public GenerationCheckpoint(GenerationRecord record, List<GenerationPatchNoteHistory.Entry> patchNotesHistory, Set<String> supersededGenerationIds,
			Set<String> supersededCatalogueStateDigests) {
		this(CURRENT_SCHEMA_VERSION, record.metadata().generationId(), record, patchNotesHistory, supersededGenerationIds, supersededCatalogueStateDigests);
	}

	public GenerationJsons.GenerationCheckpointFields toFields() {
		GenerationJsons.GenerationCheckpointFields fields = new GenerationJsons.GenerationCheckpointFields();
		fields.schemaVersion = schemaVersion;
		fields.boundaryGenerationId = boundaryGenerationId;
		fields.record = record.toFields();
		fields.patchNotesHistory = patchNotesHistory.stream().map(GenerationPatchNoteHistory.Entry::toFields).toList();
		fields.supersededGenerationIds = new TreeSet<>(supersededGenerationIds).stream().toList();
		fields.supersededCatalogueStateDigests = new TreeSet<>(supersededCatalogueStateDigests).stream().toList();
		return fields;
	}

	public static GenerationCheckpoint fromFields(GenerationJsons.GenerationCheckpointFields fields) {
		if (fields == null || fields.record == null) throw new IllegalArgumentException("Generation checkpoint is missing");
		if (fields.record.patchNotesHistory != null && !fields.record.patchNotesHistory.isEmpty())
			throw new IllegalArgumentException("Generation checkpoint must contain the exact generation record without patch-note history");
		List<GenerationPatchNoteHistory.Entry> patchNotesHistory = new ArrayList<>();
		if (fields.patchNotesHistory == null) throw new IllegalArgumentException("Generation checkpoint patch-note history is missing");
		for (var entry : fields.patchNotesHistory) patchNotesHistory.add(GenerationPatchNoteHistory.Entry.fromFields(entry));
		return new GenerationCheckpoint(fields.schemaVersion, fields.boundaryGenerationId, GenerationRecord.fromFields(fields.record),
				patchNotesHistory,
				new TreeSet<>(fields.supersededGenerationIds == null ? List.of() : fields.supersededGenerationIds),
				new TreeSet<>(fields.supersededCatalogueStateDigests == null ? List.of() : fields.supersededCatalogueStateDigests));
	}

	private static Set<String> immutableDigests(Iterable<String> values, String description) {
		TreeSet<String> result = new TreeSet<>();
		if (values != null) for (String value : values) result.add(GenerationMetadata.requireDigest(value, description));
		return Set.copyOf(result);
	}
}
