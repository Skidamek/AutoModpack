package pl.skidam.automodpack_core.modpack.generation;

import java.util.Locale;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.config.Jsons;

/** The immutable generation identity carried by a selected flat client target. */
public record GenerationTarget(String targetGenerationId, String parentGenerationId, String stateDigest) {
	private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{40}");

	public GenerationTarget {
		targetGenerationId = requireDigest(targetGenerationId, "target generation ID");
		parentGenerationId = requireOptionalDigest(parentGenerationId, "parent generation ID");
		stateDigest = requireDigest(stateDigest, "state digest");
	}

	public static GenerationTarget from(GenerationMetadata metadata) {
		return new GenerationTarget(metadata.generationId(), metadata.parentGenerationId(), metadata.stateDigest());
	}

	public static GenerationTarget fromFlat(Jsons.ModpackContentFields fields) {
		if (fields == null) throw new IllegalArgumentException("Selected modpack target is missing");
		return new GenerationTarget(fields.targetGenerationId, fields.parentGenerationId, fields.stateDigest);
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
