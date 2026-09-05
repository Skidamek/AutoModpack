package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import pl.skidam.automodpack_core.utils.HashUtils;

/** Chooses which projection jars the loader adapters should receive in {@link ModpackLoadRequest}. */
public final class ModpackLoadSelection {
	private ModpackLoadSelection() {}

	public record Jar(Path path, String sha1, Set<String> ids) {
		public Jar {
			path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
			sha1 = sha1 == null || !HashUtils.isSha1(sha1) ? null : HashUtils.normalizeSha1(sha1);
			ids = PinnedMods.ids(ids);
		}
	}

	public static List<Path> select(List<Jar> projectionJars, Set<String> liveHashes, Collection<? extends Collection<String>> liveJarIds, Collection<String> pinnedModIds) {
		Set<String> hashes = normalizeHashes(liveHashes);
		Set<String> protectedIds = PinnedMods.protectedIds(pinnedModIds, liveJarIds);
		List<Path> selected = new ArrayList<>();
		for (Jar jar : projectionJars == null ? List.<Jar>of() : projectionJars) {
			if (jar.sha1() != null && hashes.contains(jar.sha1())) continue;
			if (PinnedMods.protects(protectedIds, jar.ids())) continue;
			selected.add(jar.path());
		}
		return List.copyOf(selected);
	}

	private static Set<String> normalizeHashes(Set<String> liveHashes) {
		if (liveHashes == null || liveHashes.isEmpty()) return Set.of();
		HashSet<String> hashes = new HashSet<>();
		for (String hash : liveHashes) if (HashUtils.isSha1(hash)) hashes.add(HashUtils.normalizeSha1(hash));
		return hashes;
	}
}
