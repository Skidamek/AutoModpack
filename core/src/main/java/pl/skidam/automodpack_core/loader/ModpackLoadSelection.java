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

	/** The jars the adapters load, and every projection jar that was left out with the one fact that left it out. */
	public record Selection(List<Path> paths, List<Excluded> excluded) {
		public Selection {
			paths = List.copyOf(paths);
			excluded = List.copyOf(excluded);
		}

		public long excludedBy(Reason reason) {
			return excluded.stream().filter(excluded -> excluded.reason() == reason).count();
		}
	}

	public record Excluded(Jar jar, Reason reason) {}

	/** Why one projection jar does not load; the reader turns it into the sentence a player or a support log reads. */
	public enum Reason {
		/** The group keeps the player's own copy in the standard mods folder, so the projection copy is never authoritative. */
		EDITABLE,
		/** The standard mods folder holds a jar at the same path. */
		LIVE_PATH,
		/** The standard mods folder holds a jar with the same bytes. */
		LIVE_HASH,
		/** A pinned instance mod already provides this jar's ids. */
		PINNED
	}

	/**
	 * An editable active mod never loads from the projection: its standard-folder copy is authoritative, and a
	 * player-deleted one stays deleted. Any other projection jar whose path holds a live jar is shadowed by it.
	 * The remaining jars are deduplicated against the live mods by hash and by pinned mod ids.
	 */
	public static Selection select(List<Jar> projectionJars, Set<String> liveModPaths, Set<String> liveHashes, Collection<? extends Collection<String>> liveJarIds,
			Collection<String> pinnedModIds) {
		Set<String> livePaths = logicalPaths(liveModPaths);
		Set<String> hashes = normalizeHashes(liveHashes);
		Set<String> protectedIds = PinnedMods.protectedIds(pinnedModIds, liveJarIds);
		List<Path> selected = new ArrayList<>();
		List<Excluded> excluded = new ArrayList<>();
		for (Jar jar : projectionJars == null ? List.<Jar>of() : projectionJars) {
			Reason reason = exclusion(jar, livePaths, hashes, protectedIds);
			if (reason == null) {
				selected.add(jar.path());
				continue;
			}
			excluded.add(new Excluded(jar, reason));
		}
		return new Selection(selected, excluded);
	}

	private static Reason exclusion(Jar jar, Set<String> livePaths, Set<String> liveHashes, Set<String> protectedIds) {
		if (jar.editable()) return Reason.EDITABLE;
		if (livePaths.contains(jar.logicalPath())) return Reason.LIVE_PATH;
		if (jar.sha1() != null && liveHashes.contains(jar.sha1())) return Reason.LIVE_HASH;
		if (PinnedMods.protects(protectedIds, jar.ids())) return Reason.PINNED;
		return null;
	}

	private static Set<String> logicalPaths(Set<String> paths) {
		if (paths == null || paths.isEmpty()) return Set.of();
		HashSet<String> normalized = new HashSet<>();
		for (String path : paths) normalized.add(LogicalPath.normalize(path));
		return normalized;
	}

	private static Set<String> normalizeHashes(Set<String> hashes) {
		if (hashes == null || hashes.isEmpty()) return Set.of();
		HashSet<String> normalized = new HashSet<>();
		for (String hash : hashes) if (HashUtils.isSha1(hash)) normalized.add(HashUtils.normalizeSha1(hash));
		return normalized;
	}
}
