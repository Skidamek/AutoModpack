package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Chooses which projection jars the loader adapters should receive in {@link ModpackLoadRequest}. */
public final class ModpackLoadSelection {
	private ModpackLoadSelection() {}

	public record Jar(Path path, String logicalPath, String sha1, boolean editable, Set<String> ids) {
		public Jar {
			path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
			logicalPath = LogicalPath.normalize(Objects.requireNonNull(logicalPath, "logicalPath"));
			sha1 = sha1 == null || !HashUtils.isSha1(sha1) ? null : HashUtils.normalizeSha1(sha1);
			ids = PinnedMods.ids(ids);
		}
	}

	/**
	 * An editable active mod never loads from the projection: its standard-folder copy is authoritative, and a
	 * player-deleted one stays deleted. Any other projection jar whose path holds a live jar is shadowed by it.
	 * The remaining jars are deduplicated against the live mods by hash and by pinned mod ids.
	 */
	public static List<Path> select(List<Jar> projectionJars, Set<String> liveModPaths, Set<String> liveHashes, Collection<? extends Collection<String>> liveJarIds, Collection<String> pinnedModIds) {
		Set<String> livePaths = logicalPaths(liveModPaths);
		Set<String> hashes = normalizeHashes(liveHashes);
		Set<String> protectedIds = PinnedMods.protectedIds(pinnedModIds, liveJarIds);
		List<Path> selected = new ArrayList<>();
		for (Jar jar : projectionJars == null ? List.<Jar>of() : projectionJars) {
			if (jar.editable()) continue;
			if (livePaths.contains(jar.logicalPath())) continue;
			if (jar.sha1() != null && hashes.contains(jar.sha1())) continue;
			if (PinnedMods.protects(protectedIds, jar.ids())) continue;
			selected.add(jar.path());
		}
		return List.copyOf(selected);
	}

	private static Set<String> logicalPaths(Set<String> paths) {
		if (paths == null || paths.isEmpty()) return Set.of();
		HashSet<String> normalized = new HashSet<>();
		for (String path : paths) normalized.add(LogicalPath.normalize(path));
		return normalized;
	}

	private static Set<String> normalizeHashes(Set<String> liveHashes) {
		if (liveHashes == null || liveHashes.isEmpty()) return Set.of();
		HashSet<String> hashes = new HashSet<>();
		for (String hash : liveHashes) if (HashUtils.isSha1(hash)) hashes.add(HashUtils.normalizeSha1(hash));
		return hashes;
	}
}
