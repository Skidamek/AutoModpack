package pl.skidam.automodpack_core.modpack.generation;

import java.util.Objects;

import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Deduplicated immutable catalogue state referenced by generation commits. */
public record CatalogueSnapshot(GroupManifest manifest, String stateDigest) {

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
		if (!HashUtils.isCanonicalSha1(value))
			throw new IllegalArgumentException("Invalid canonical " + name);
		return value;
	}
}
