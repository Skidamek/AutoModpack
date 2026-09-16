package pl.skidam.automodpack_core.modpack.candidate;

import java.io.IOException;
import java.util.*;

import pl.skidam.automodpack_core.modpack.group.GroupManifest;

public record ModpackCandidate(
		GroupManifest manifest,
		NavigableMap<String, StagedObject> objects,
		NavigableMap<String, CandidateProvenance> provenance,
		List<ExcludedCandidate> exclusions,
		List<ShadowedCandidate> shadows) implements AutoCloseable {
	public ModpackCandidate {
		TreeMap<String, StagedObject> stagedObjects = new TreeMap<>();
		if (objects != null)
			for (var entry : objects.entrySet()) {
				String sha1 = entry.getKey().toLowerCase(Locale.ROOT);
				if (stagedObjects.containsKey(sha1)) throw new IllegalArgumentException("Duplicate staged object SHA-1: " + sha1);
				StagedObject object = Objects.requireNonNull(entry.getValue());
				if (!sha1.equals(object.sha1())) throw new IllegalArgumentException("Staged object key does not match SHA-1");
				stagedObjects.put(sha1, object);
			}
		objects = Collections.unmodifiableNavigableMap(stagedObjects);
		TreeMap<String, CandidateProvenance> provenanceMap = new TreeMap<>();
		if (provenance != null) provenanceMap.putAll(provenance);
		provenance = Collections.unmodifiableNavigableMap(provenanceMap);
		exclusions = exclusions == null ? List.of() : exclusions.stream().sorted().toList();
		shadows = shadows == null ? List.of() : shadows.stream().sorted().toList();
	}

	@Override
	public void close() throws IOException {
		IOException failure = null;
		for (StagedObject object : objects.values())
			try {
				object.delete();
			} catch (IOException e) {
				if (failure == null) failure = e;
				else failure.addSuppressed(e);
			}
		if (failure != null) throw failure;
	}

	public static String provenanceKey(String groupId, String logicalPath) {
		return groupId + '\0' + logicalPath;
	}
}
