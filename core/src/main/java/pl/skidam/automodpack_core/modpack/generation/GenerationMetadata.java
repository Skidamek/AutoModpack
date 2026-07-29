package pl.skidam.automodpack_core.modpack.generation;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.config.Jsons;

public record GenerationMetadata(
		int schemaVersion,
		String generationId,
		String parentGenerationId,
		Instant createdAt,
		String stateDigest,
		String patchNotes,
		String patchNotesDigest,
		String rollbackTargetGenerationId) {
	public static final int CURRENT_SCHEMA_VERSION = 1;
	public static final String ROOT_PARENT = "";
	public static final String NO_ROLLBACK_TARGET = "";
	private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{40}");

	public GenerationMetadata {
		if (schemaVersion != CURRENT_SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported generation schema version: " + schemaVersion);
		generationId = requireDigest(generationId, "generation ID");
		parentGenerationId = requireOptionalDigest(parentGenerationId, "parent generation ID");
		createdAt = Objects.requireNonNull(createdAt, "createdAt");
		stateDigest = requireDigest(stateDigest, "state digest");
		patchNotes = requireNormalizedNotes(patchNotes);
		patchNotesDigest = requireDigest(patchNotesDigest, "patch notes digest");
		rollbackTargetGenerationId = requireOptionalDigest(rollbackTargetGenerationId, "rollback target generation ID");
		if (!rollbackTargetGenerationId.isEmpty()) throw new IllegalArgumentException("Rollback targets are not supported yet");
	}

	public static String normalizeNotes(String notes) {
		if (notes == null || notes.isEmpty()) return "";
		return notes.replace("\r\n", "\n").replace('\r', '\n');
	}

	public Jsons.CompleteModpackContentFields.GenerationFields toFields() {
		Jsons.CompleteModpackContentFields.GenerationFields fields = new Jsons.CompleteModpackContentFields.GenerationFields();
		fields.schemaVersion = schemaVersion;
		fields.generationId = generationId;
		fields.parentGenerationId = parentGenerationId;
		fields.createdAt = createdAt.toString();
		fields.stateDigest = stateDigest;
		fields.patchNotes = patchNotes;
		fields.patchNotesDigest = patchNotesDigest;
		fields.rollbackTargetGenerationId = rollbackTargetGenerationId;
		return fields;
	}

	public static GenerationMetadata fromFields(Jsons.CompleteModpackContentFields.GenerationFields fields) {
		if (fields == null) throw new IllegalArgumentException("Generation metadata is missing");
		String createdAtText = Objects.requireNonNull(fields.createdAt, "Generation creation timestamp is missing");
		Instant createdAt;
		try {
			createdAt = Instant.parse(createdAtText);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid generation creation timestamp", e);
		}
		if (!createdAt.toString().equals(createdAtText)) throw new IllegalArgumentException("Generation creation timestamp is not canonical");
		return new GenerationMetadata(fields.schemaVersion, fields.generationId, fields.parentGenerationId, createdAt, fields.stateDigest,
				fields.patchNotes, fields.patchNotesDigest, fields.rollbackTargetGenerationId);
	}

	private static String requireDigest(String value, String name) {
		if (value == null || !DIGEST.matcher(value).matches() || !value.equals(value.toLowerCase(Locale.ROOT)))
			throw new IllegalArgumentException("Invalid canonical " + name);
		return value;
	}

	private static String requireOptionalDigest(String value, String name) {
		if (value == null) throw new IllegalArgumentException("Missing " + name);
		if (value.isEmpty()) return ROOT_PARENT;
		return requireDigest(value, name);
	}

	private static String requireNormalizedNotes(String value) {
		if (value == null) throw new IllegalArgumentException("Patch notes are missing");
		if (!value.equals(normalizeNotes(value))) throw new IllegalArgumentException("Patch notes must use LF line endings");
		return value;
	}
}
