package pl.skidam.automodpack_core.modpack.generation;

import java.time.Instant;
import java.util.Objects;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;

public record GenerationRecord(GroupManifest manifest, GenerationMetadata metadata) {
	public GenerationRecord {
		manifest = Objects.requireNonNull(manifest, "manifest");
		metadata = Objects.requireNonNull(metadata, "metadata");
		String stateDigest = GenerationIdentity.stateDigest(manifest);
		if (!stateDigest.equals(metadata.stateDigest())) throw new IllegalArgumentException("Generation state digest does not match catalogue");
		String patchNotesDigest = GenerationIdentity.patchNotesDigest(metadata.patchNotes());
		if (!patchNotesDigest.equals(metadata.patchNotesDigest())) throw new IllegalArgumentException("Generation patch notes digest does not match notes");
		String generationId = GenerationIdentity.generationId(metadata.schemaVersion(), manifest.modpackId(), metadata.parentGenerationId(),
				metadata.createdAt().toString(), metadata.stateDigest(), metadata.patchNotesDigest(), metadata.rollbackTargetGenerationId());
		if (!generationId.equals(metadata.generationId())) throw new IllegalArgumentException("Generation ID does not match generation metadata");
	}

	public static GenerationRecord create(GroupManifest manifest, String parentGenerationId, Instant createdAt, String patchNotes) {
		String normalizedNotes = GenerationMetadata.normalizeNotes(patchNotes);
		String stateDigest = GenerationIdentity.stateDigest(manifest);
		String notesDigest = GenerationIdentity.patchNotesDigest(normalizedNotes);
		String parent = parentGenerationId == null ? GenerationMetadata.ROOT_PARENT : parentGenerationId;
		String generationId = GenerationIdentity.generationId(GenerationMetadata.CURRENT_SCHEMA_VERSION, manifest.modpackId(), parent, createdAt.toString(), stateDigest,
				notesDigest, "");
		return new GenerationRecord(manifest, new GenerationMetadata(GenerationMetadata.CURRENT_SCHEMA_VERSION, generationId, parent, createdAt, stateDigest,
				normalizedNotes, notesDigest, ""));
	}

	public Jsons.CompleteModpackContentFields toFields() {
		Jsons.CompleteModpackContentFields fields = manifest.toFields();
		fields.generation = metadata.toFields();
		return fields;
	}

	public static GenerationRecord fromFields(Jsons.CompleteModpackContentFields fields) {
		GroupManifest manifest = GroupManifestValidator.validate(fields);
		GenerationMetadata metadata = GenerationMetadata.fromFields(fields.generation);
		return new GenerationRecord(manifest, metadata);
	}
}
