package pl.skidam.automodpack_core.modpack.generation;

import java.util.Locale;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;

/** The immutable generation identity carried by a selected flat client target. */
public record GenerationTarget(String modpackId, String targetGenerationId, String parentGenerationId, String stateDigest, String ledgerDigest) {
	private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{40}");

	public GenerationTarget {
		modpackId = ModpackId.requireValid(modpackId);
		targetGenerationId = requireDigest(targetGenerationId, "target generation ID");
		parentGenerationId = requireOptionalDigest(parentGenerationId, "parent generation ID");
		stateDigest = requireDigest(stateDigest, "state digest");
		ledgerDigest = requireDigest(ledgerDigest, "ledger digest");
	}

	public static GenerationTarget from(GenerationRecord record) {
		if (record == null) throw new IllegalArgumentException("Generation record is missing");
		return new GenerationTarget(record.manifest().modpackId(), record.metadata().generationId(), record.metadata().parentGenerationId(),
				record.metadata().stateDigest(), record.metadata().ledgerDigest());
	}

	public static GenerationTarget fromFlat(Jsons.ModpackContentFields fields) {
		if (fields == null) throw new IllegalArgumentException("Selected modpack target is missing");
		String modpackId = ModpackId.requireValid(fields.modpackId);
		OwnershipLedger ledger = OwnershipLedger.fromFields(fields.ownershipLedger);
		if (!modpackId.equals(ledger.modpackId())) throw new IllegalArgumentException("Selected target ledger modpack ID does not match target");
		return new GenerationTarget(modpackId, fields.targetGenerationId, fields.parentGenerationId, fields.stateDigest, ledger.digest());
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
