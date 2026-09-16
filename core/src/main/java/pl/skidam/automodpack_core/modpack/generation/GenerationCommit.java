package pl.skidam.automodpack_core.modpack.generation;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;

/** Small immutable generation metadata envelope linking a catalogue and ownership delta. */
public record GenerationCommit(
		int schemaVersion,
		String generationId,
		String parentGenerationId,
		String modpackId,
		Instant createdAt,
		String stateDigest,
		String ledgerDigest,
		String ownershipDeltaDigest,
		String patchNotes,
		String patchNotesDigest,
		String rollbackTargetGenerationId) {
	private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{40}");

	public GenerationCommit {
		if (schemaVersion != GenerationMetadata.CURRENT_SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported generation commit schema version: " + schemaVersion);
		generationId = requireDigest(generationId, "generation ID");
		parentGenerationId = requireOptionalDigest(parentGenerationId, "parent generation ID");
		modpackId = ModpackId.requireValid(modpackId);
		createdAt = Objects.requireNonNull(createdAt, "generation creation timestamp");
		stateDigest = requireDigest(stateDigest, "state digest");
		ledgerDigest = requireDigest(ledgerDigest, "ledger digest");
		ownershipDeltaDigest = requireDigest(ownershipDeltaDigest, "ownership delta digest");
		String normalizedNotes = GenerationMetadata.validateNotes(patchNotes);
		if (!normalizedNotes.equals(patchNotes)) throw new IllegalArgumentException("Patch notes must use LF line endings");
		patchNotes = normalizedNotes;
		patchNotesDigest = requireDigest(patchNotesDigest, "patch notes digest");
		rollbackTargetGenerationId = requireOptionalDigest(rollbackTargetGenerationId, "rollback target generation ID");
		String expected = GenerationIdentity.generationId(schemaVersion, modpackId, parentGenerationId, createdAt.toString(), stateDigest, ledgerDigest,
				patchNotesDigest, rollbackTargetGenerationId);
		if (!generationId.equals(expected)) throw new IllegalArgumentException("Generation commit identity does not match its metadata");
	}

	public static GenerationCommit from(GenerationRecord record, OwnershipDelta delta) {
		Objects.requireNonNull(record, "generation record");
		Objects.requireNonNull(delta, "ownership delta");
		GenerationMetadata metadata = record.metadata();
		return new GenerationCommit(metadata.schemaVersion(), metadata.generationId(), metadata.parentGenerationId(), record.manifest().modpackId(), metadata.createdAt(),
				metadata.stateDigest(), metadata.ledgerDigest(), delta.digest(), metadata.patchNotes(), metadata.patchNotesDigest(), metadata.rollbackTargetGenerationId());
	}

	public GenerationMetadata metadata() {
		return new GenerationMetadata(schemaVersion, generationId, parentGenerationId, createdAt, stateDigest, ledgerDigest, patchNotes, patchNotesDigest,
				rollbackTargetGenerationId);
	}

	public Jsons.GenerationCommitFields toFields() {
		Jsons.GenerationCommitFields fields = new Jsons.GenerationCommitFields();
		fields.schemaVersion = schemaVersion;
		fields.generationId = generationId;
		fields.parentGenerationId = parentGenerationId;
		fields.modpackId = modpackId;
		fields.createdAt = createdAt.toString();
		fields.stateDigest = stateDigest;
		fields.ledgerDigest = ledgerDigest;
		fields.ownershipDeltaDigest = ownershipDeltaDigest;
		fields.patchNotes = patchNotes;
		fields.patchNotesDigest = patchNotesDigest;
		fields.rollbackTargetGenerationId = rollbackTargetGenerationId;
		return fields;
	}

	public static GenerationCommit fromFields(Jsons.GenerationCommitFields fields) {
		if (fields == null) throw new IllegalArgumentException("Generation commit is missing");
		String createdAtText = Objects.requireNonNull(fields.createdAt, "Generation commit creation timestamp is missing");
		Instant createdAt;
		try {
			createdAt = Instant.parse(createdAtText);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid generation commit creation timestamp", e);
		}
		if (!createdAt.toString().equals(createdAtText)) throw new IllegalArgumentException("Generation commit creation timestamp is not canonical");
		return new GenerationCommit(fields.schemaVersion, fields.generationId, fields.parentGenerationId, fields.modpackId, createdAt, fields.stateDigest,
				fields.ledgerDigest, fields.ownershipDeltaDigest, fields.patchNotes, fields.patchNotesDigest, fields.rollbackTargetGenerationId);
	}

	private static String requireDigest(String value, String name) {
		if (value == null || !DIGEST.matcher(value).matches() || !value.equals(value.toLowerCase(Locale.ROOT)))
			throw new IllegalArgumentException("Invalid canonical " + name);
		return value;
	}

	private static String requireOptionalDigest(String value, String name) {
		if (value == null) throw new IllegalArgumentException("Missing " + name);
		if (value.isEmpty()) return GenerationMetadata.ROOT_PARENT;
		return requireDigest(value, name);
	}
}
