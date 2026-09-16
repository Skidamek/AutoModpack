package pl.skidam.automodpack_core.modpack.candidate;

import java.nio.file.Path;
import java.util.Objects;

public record CandidateSource(String groupId, String logicalPath, SourceKind kind, Path sourcePath, String matchedRule) implements Comparable<CandidateSource> {
	public CandidateSource {
		Objects.requireNonNull(groupId);
		Objects.requireNonNull(logicalPath);
		Objects.requireNonNull(kind);
		Objects.requireNonNull(sourcePath);
		sourcePath = sourcePath.toAbsolutePath().normalize();
	}

	@Override
	public int compareTo(CandidateSource other) {
		int group = groupId.compareTo(other.groupId);
		if (group != 0) return group;
		int path = logicalPath.compareTo(other.logicalPath);
		if (path != 0) return path;
		int kindOrder = kind.compareTo(other.kind);
		return kindOrder != 0 ? kindOrder : sourcePath.toString().compareTo(other.sourcePath.toString());
	}

	public enum SourceKind {
		GROUP_DIRECTORY,
		SYNCED_ROOT
	}
}
