package pl.skidam.automodpack_core.modpack.candidate;

public record ExcludedCandidate(CandidateSource source, Reason reason, String message) implements Comparable<ExcludedCandidate> {
	@Override
	public int compareTo(ExcludedCandidate other) {
		int sourceOrder = source.compareTo(other.source);
		return sourceOrder != 0 ? sourceOrder : reason.compareTo(other.reason);
	}

	public enum Reason {
		EXCLUDED_BY_RULE,
		EMPTY_FILE,
		HIDDEN_FILE,
		TEMPORARY_FILE,
		DISABLED_FILE,
		BACKUP_FILE,
		SERVER_SIDE_MOD,
		INTERNAL_FILE
	}
}
