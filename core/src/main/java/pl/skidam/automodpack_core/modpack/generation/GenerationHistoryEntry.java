package pl.skidam.automodpack_core.modpack.generation;

import java.util.Objects;

import pl.skidam.automodpack_core.modpack.group.GroupManifest;

/** Lightweight technical-history entry. Historical ledger state is not part of the history list. */
public record GenerationHistoryEntry(GroupManifest manifest, GenerationMetadata metadata) {
	public GenerationHistoryEntry {
		manifest = Objects.requireNonNull(manifest, "generation history catalogue");
		metadata = Objects.requireNonNull(metadata, "generation history metadata");
		if (!GenerationIdentity.stateDigest(manifest).equals(metadata.stateDigest()))
			throw new IllegalArgumentException("Generation history state digest does not match catalogue");
	}

	public static GenerationHistoryEntry from(GenerationCommit commit, CatalogueSnapshot snapshot) {
		Objects.requireNonNull(commit, "generation commit");
		Objects.requireNonNull(snapshot, "generation catalogue");
		if (!commit.modpackId().equals(snapshot.manifest().modpackId()) || !commit.stateDigest().equals(snapshot.stateDigest()))
			throw new IllegalArgumentException("Generation history commit does not match catalogue");
		return new GenerationHistoryEntry(snapshot.manifest(), commit.metadata());
	}
}
