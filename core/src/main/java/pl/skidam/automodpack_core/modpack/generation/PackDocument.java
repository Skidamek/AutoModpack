package pl.skidam.automodpack_core.modpack.generation;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;

import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;

/** One served generation: its policy document, its content token, its ledger projection, and its waiting track. */
public record PackDocument(GroupManifest manifest, String contentToken, String policySha1, Instant createdAt, OwnershipLedger ownershipLedger, String waitingMusicSha1) {
	public PackDocument {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(contentToken, "content token");
		Objects.requireNonNull(policySha1, "policy document hash");
		createdAt = Objects.requireNonNull(createdAt, "createdAt");
		Objects.requireNonNull(ownershipLedger, "ownership ledger");
		waitingMusicSha1 = waitingMusicSha1 == null ? "" : waitingMusicSha1.trim().toLowerCase(Locale.ROOT);
		if (!ContentTree.fromManifest(manifest).token().equals(contentToken))
			throw new IllegalArgumentException("Content token does not match the policy document's files");
		if (!manifest.modpackId().equals(ownershipLedger.modpackId())) throw new IllegalArgumentException("Ledger modpack ID does not match the policy document");
	}

	public static PackDocument create(GroupManifest manifest, String policySha1, Instant createdAt, OwnershipLedger parentLedger) {
		OwnershipLedger base = parentLedger == null ? OwnershipLedger.empty(manifest.modpackId()) : parentLedger;
		return new PackDocument(manifest, ContentTree.fromManifest(manifest).token(), policySha1, createdAt, OwnershipLedger.materialize(base, manifest), "");
	}

	public static PackDocument fromFields(GenerationJsons.HeadDocumentFields fields) {
		if (fields == null) throw new IllegalArgumentException("Head document is missing");
		Instant createdAt;
		try {
			createdAt = Instant.parse(fields.createdAt);
		} catch (DateTimeParseException | NullPointerException e) {
			throw new IllegalArgumentException("Head document is missing its creation timestamp", e);
		}
		return new PackDocument(GroupManifestValidator.validate(fields.policy), fields.contentToken, fields.policySha1, createdAt, OwnershipLedger.fromFields(fields.ownershipLedger),
				fields.waitingMusicSha1);
	}
}
