package pl.skidam.automodpack_core.modpack.candidate;

import java.nio.file.Path;
import java.util.*;

import pl.skidam.automodpack_core.modpack.group.GroupManifest;

public record ModpackCandidate(
		GroupManifest manifest,
		NavigableMap<String, Path> hostedPaths,
		NavigableMap<String, CandidateProvenance> provenance,
		List<ExcludedCandidate> exclusions,
		List<ShadowedCandidate> shadows) {
	public ModpackCandidate {
		TreeMap<String, Path> paths = new TreeMap<>();
		if (hostedPaths != null) paths.putAll(hostedPaths);
		hostedPaths = Collections.unmodifiableNavigableMap(paths);
		TreeMap<String, CandidateProvenance> provenanceMap = new TreeMap<>();
		if (provenance != null) provenanceMap.putAll(provenance);
		provenance = Collections.unmodifiableNavigableMap(provenanceMap);
		exclusions = exclusions == null ? List.of() : exclusions.stream().sorted().toList();
		shadows = shadows == null ? List.of() : shadows.stream().sorted().toList();
	}

	public static String provenanceKey(String groupId, String logicalPath) {
		return groupId + '\0' + logicalPath;
	}
}
