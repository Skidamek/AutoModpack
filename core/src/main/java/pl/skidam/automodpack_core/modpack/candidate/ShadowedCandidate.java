package pl.skidam.automodpack_core.modpack.candidate;

public record ShadowedCandidate(CandidateSource selected, CandidateSource shadowed, Relationship relationship) implements Comparable<ShadowedCandidate> {
	@Override
	public int compareTo(ShadowedCandidate other) {
		return selected.compareTo(other.selected);
	}

	public enum Relationship {
		IDENTICAL_CONTENT,
		DIFFERENT_CONTENT,
		NOT_COMPARED
	}
}
