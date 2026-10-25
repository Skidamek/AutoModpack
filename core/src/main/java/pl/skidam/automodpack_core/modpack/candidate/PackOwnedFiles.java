package pl.skidam.automodpack_core.modpack.candidate;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * What a generation on an instance must never republish as its own: every path an installed pack manages, whatever
 * bytes the player left there, and at every other path the exact bytes a pack once held there.
 */
public final class PackOwnedFiles {
	public static final PackOwnedFiles NONE = new PackOwnedFiles(Set.of(), Map.of());

	private final Set<String> managedPaths;
	private final Map<String, Set<String>> historicHashesByPath;

	private PackOwnedFiles(Set<String> managedPaths, Map<String, Set<String>> historicHashesByPath) {
		this.managedPaths = managedPaths;
		this.historicHashesByPath = historicHashesByPath;
	}

	public boolean owns(String logicalPath, String sha1) {
		if (logicalPath == null) return false;
		if (managedPaths.contains(logicalPath)) return true;
		Set<String> hashes = historicHashesByPath.get(logicalPath);
		return hashes != null && HashUtils.isSha1(sha1) && hashes.contains(HashUtils.normalizeSha1(sha1));
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private final Set<String> managedPaths = new TreeSet<>();
		private final Map<String, Set<String>> historicHashesByPath = new TreeMap<>();

		/** A path an installed pack manages now: owned whatever its bytes. */
		public Builder managed(String logicalPath) {
			managedPaths.add(Objects.requireNonNull(logicalPath, "logical path"));
			return this;
		}

		/** Bytes a pack once held at a path: owned only while the file still holds exactly them. */
		public Builder historic(String logicalPath, String sha1) {
			Objects.requireNonNull(logicalPath, "logical path");
			if (HashUtils.isSha1(sha1)) historicHashesByPath.computeIfAbsent(logicalPath, ignored -> new TreeSet<>()).add(HashUtils.normalizeSha1(sha1));
			return this;
		}

		public PackOwnedFiles build() {
			if (managedPaths.isEmpty() && historicHashesByPath.isEmpty()) return NONE;
			Map<String, Set<String>> frozen = new TreeMap<>();
			historicHashesByPath.forEach((path, hashes) -> frozen.put(path, Set.copyOf(hashes)));
			return new PackOwnedFiles(Set.copyOf(managedPaths), Map.copyOf(frozen));
		}
	}
}
