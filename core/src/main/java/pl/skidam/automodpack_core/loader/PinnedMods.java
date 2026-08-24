package pl.skidam.automodpack_core.loader;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.Constants;

/**
 * Instance-wide client pins: listed mod ids that are present in live {@code mods/} stay there,
 * and overlapping jars in the active projection are not loaded.
 */
public final class PinnedMods {
	private PinnedMods() {}

	public static List<String> normalize(Collection<String> listed) {
		if (listed == null || listed.isEmpty()) return List.of();
		LinkedHashSet<String> unique = new LinkedHashSet<>();
		for (String value : listed) {
			String id = indexId(value);
			if (id != null) unique.add(id);
		}
		return List.copyOf(unique);
	}

	public static Set<String> index(Collection<String> listed) {
		return Set.copyOf(normalize(listed));
	}

	public static Set<String> ids(Collection<String> jarIds) {
		if (jarIds == null || jarIds.isEmpty()) return Set.of();
		Set<String> ids = new TreeSet<>();
		for (String value : jarIds) {
			String id = canonicalize(value);
			if (id != null) ids.add(id);
		}
		return Set.copyOf(ids);
	}

	public static boolean matches(Collection<String> pinned, Collection<String> jarIds) {
		if (pinned == null || pinned.isEmpty() || jarIds == null || jarIds.isEmpty()) return false;
		Set<String> pins = ids(pinned);
		if (pins.isEmpty()) return false;
		for (String id : ids(jarIds)) if (pins.contains(id)) return true;
		return false;
	}

	/** Union of ids from live jars that match a listed pin. Projection jars intersecting this set are not loaded. */
	public static Set<String> protectedIds(Collection<String> listed, Collection<? extends Collection<String>> liveJarIds) {
		Set<String> pinned = index(listed);
		if (pinned.isEmpty() || liveJarIds == null || liveJarIds.isEmpty()) return Set.of();
		Set<String> protectedIds = new TreeSet<>();
		for (Collection<String> jarIds : liveJarIds) {
			Set<String> ids = ids(jarIds);
			if (matches(pinned, ids)) protectedIds.addAll(ids);
		}
		return Set.copyOf(protectedIds);
	}

	public static boolean protects(Collection<String> protectedIds, Collection<String> jarIds) {
		return matches(protectedIds, jarIds);
	}

	private static String indexId(String value) {
		String id = canonicalize(value);
		if (id == null) return null;
		if (id.equals(Constants.MOD_ID) || id.equals("automodpack_mod")) return null;
		return id;
	}

	private static String canonicalize(String value) {
		if (value == null) return null;
		String id = value.strip().toLowerCase(Locale.ROOT);
		return id.isEmpty() ? null : id;
	}
}
