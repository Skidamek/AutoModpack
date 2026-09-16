package pl.skidam.automodpack_core.modpack.generation;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;

/** Deduplicated immutable catalogue state referenced by generation commits. */
public record CatalogueSnapshot(GroupManifest manifest, String stateDigest) {
	private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{40}");

	public CatalogueSnapshot {
		manifest = Objects.requireNonNull(manifest, "catalogue manifest");
		stateDigest = requireDigest(stateDigest, "catalogue state digest");
		String expected = GenerationIdentity.stateDigest(manifest);
		if (!stateDigest.equals(expected)) throw new IllegalArgumentException("Catalogue snapshot digest does not match content");
	}

	public static CatalogueSnapshot from(GroupManifest manifest) {
		Objects.requireNonNull(manifest, "catalogue manifest");
		return new CatalogueSnapshot(manifest, GenerationIdentity.stateDigest(manifest));
	}

	public GenerationJsons.CatalogueSnapshotFields toFields() {
		GenerationJsons.CatalogueSnapshotFields fields = new GenerationJsons.CatalogueSnapshotFields();
		fields.stateDigest = stateDigest;
		fields.catalogue = manifest.toFields();
		return fields;
	}

	public static CatalogueSnapshot fromFields(GenerationJsons.CatalogueSnapshotFields fields) {
		if (fields == null || fields.catalogue == null) throw new IllegalArgumentException("Catalogue snapshot is missing");
		return new CatalogueSnapshot(GroupManifestValidator.validate(fields.catalogue), fields.stateDigest);
	}

	private static String requireDigest(String value, String name) {
		if (value == null || !DIGEST.matcher(value).matches() || !value.equals(value.toLowerCase(Locale.ROOT)))
			throw new IllegalArgumentException("Invalid canonical " + name);
		return value;
	}
}
