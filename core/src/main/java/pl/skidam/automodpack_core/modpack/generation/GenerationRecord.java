package pl.skidam.automodpack_core.modpack.generation;

import java.time.Instant;
import java.util.Objects;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;

public record GenerationRecord(GroupManifest manifest, GenerationMetadata metadata, OwnershipLedger ownershipLedger) {
	public GenerationRecord {
		manifest = Objects.requireNonNull(manifest, "manifest");
		metadata = Objects.requireNonNull(metadata, "metadata");
		ownershipLedger = Objects.requireNonNull(ownershipLedger, "ownership ledger");
		String stateDigest = GenerationIdentity.stateDigest(manifest);
		if (!stateDigest.equals(metadata.stateDigest())) throw new IllegalArgumentException("Generation state digest does not match catalogue");
		if (!manifest.modpackId().equals(ownershipLedger.modpackId())) throw new IllegalArgumentException("Generation ledger modpack ID does not match catalogue");
		if (!ownershipLedger.digest().equals(metadata.ledgerDigest())) throw new IllegalArgumentException("Generation ledger digest does not match metadata");
		String patchNotesDigest = GenerationIdentity.patchNotesDigest(metadata.patchNotes());
		if (!patchNotesDigest.equals(metadata.patchNotesDigest())) throw new IllegalArgumentException("Generation patch notes digest does not match notes");
		String generationId = GenerationIdentity.generationId(metadata.schemaVersion(), manifest.modpackId(), metadata.parentGenerationId(),
				metadata.createdAt().toString(), metadata.stateDigest(), metadata.ledgerDigest(), metadata.patchNotesDigest(), metadata.rollbackTargetGenerationId());
		if (!generationId.equals(metadata.generationId())) throw new IllegalArgumentException("Generation ID does not match generation metadata");
	}

	public static GenerationRecord create(GroupManifest manifest, GenerationRecord parent, Instant createdAt, String patchNotes) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(createdAt, "createdAt");
		String parentGenerationId = parent == null ? GenerationMetadata.ROOT_PARENT : parent.metadata().generationId();
		OwnershipLedger base = parent == null ? OwnershipLedger.empty(manifest.modpackId()) : parent.ownershipLedger();
		if (!base.modpackId().equals(manifest.modpackId())) throw new IllegalArgumentException("Parent and catalogue modpack IDs disagree");
		String normalizedNotes = GenerationMetadata.validateNotes(patchNotes);
		String stateDigest = GenerationIdentity.stateDigest(manifest);
		String notesDigest = GenerationIdentity.patchNotesDigest(normalizedNotes);
		OwnershipLedger provisionalLedger = OwnershipLedger.materializeWithoutGeneration(base, manifest);
		String generationId = GenerationIdentity.generationId(GenerationMetadata.CURRENT_SCHEMA_VERSION, manifest.modpackId(), parentGenerationId,
				createdAt.toString(), stateDigest, provisionalLedger.digest(), notesDigest, "");
		OwnershipLedger ledger = OwnershipLedger.materialize(base, manifest, generationId);
		GenerationMetadata metadata = new GenerationMetadata(GenerationMetadata.CURRENT_SCHEMA_VERSION, generationId, parentGenerationId, createdAt, stateDigest,
				ledger.digest(), normalizedNotes, notesDigest, "");
		return new GenerationRecord(manifest, metadata, ledger);
	}

	public Jsons.CompleteModpackContentFields toFields() {
		Jsons.CompleteModpackContentFields fields = manifest.toFields();
		fields.ownershipLedger = ownershipLedger.toFields();
		fields.generation = metadata.toFields();
		return fields;
	}

	public static GenerationRecord fromFields(Jsons.CompleteModpackContentFields fields) {
		GroupManifest manifest = GroupManifestValidator.validate(fields);
		GenerationMetadata metadata = GenerationMetadata.fromFields(fields.generation);
		OwnershipLedger ledger = OwnershipLedger.fromFields(fields.ownershipLedger);
		return new GenerationRecord(manifest, metadata, ledger);
	}
}
