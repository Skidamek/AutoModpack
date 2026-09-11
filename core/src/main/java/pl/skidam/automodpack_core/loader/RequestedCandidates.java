package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Selects the discovered mod candidates a {@link ModpackLoadRequest} asks to load: the requested
 * roots plus everything transitively nested under them. Nesting is recursive (jar-in-jar-in-jar),
 * so the subtree is walked to its full depth - stopping one level short strands the nested mods of
 * nested mods and resolution fails with a missing dependency.
 *
 * @param <T>
 *            the loader-specific candidate type
 */
public final class RequestedCandidates {
	private RequestedCandidates() {}

	/** The loader-specific surface of a discovered candidate needed for the selection. */
	public interface Accessor<T> {
		boolean isRoot(T candidate);

		/** Paths of a root candidate, empty for candidates without a path (builtins). */
		List<Path> paths(T candidate);

		/** Candidates nested directly inside this candidate, possibly empty. */
		Collection<T> nestedMods(T candidate);
	}

	/**
	 * Keeps the requested roots and their whole nested subtrees, in no guaranteed order. Roots
	 * outside the projection are always requested; a root inside the projection is requested only
	 * if the jar is among the {@link ModpackLoadRequest}'s modpack mods.
	 */
	public static <T> List<T> keep(Collection<T> candidates, Accessor<T> accessor, Predicate<Path> requested) {
		List<T> kept = new ArrayList<>(candidates.size());
		Set<T> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<T> queue = new ArrayDeque<>();

		for (T candidate : candidates) {
			if (!accessor.isRoot(candidate)) continue;
			List<Path> paths = accessor.paths(candidate);
			if (paths == null || paths.isEmpty() || requested.test(paths.get(0))) queue.add(candidate);
		}

		while (!queue.isEmpty()) {
			T candidate = queue.poll();
			if (!visited.add(candidate)) continue;
			kept.add(candidate);
			queue.addAll(accessor.nestedMods(candidate));
		}

		return kept;
	}
}
