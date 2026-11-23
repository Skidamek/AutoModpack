package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Selects the discovered mod candidates a {@link ModpackLoadRequest} asks to load: the requested roots plus everything
 * transitively nested under them. Nesting is recursive (jar-in-jar-in-jar), so the subtree is walked to its full depth -
 * stopping one level short strands the nested mods of nested mods and resolution fails with a missing dependency.
 *
 * <p>
 * The ids the instance already provides are claimed: the boot resolved those mods, and they are what answers the pack
 * mods' dependencies. A projection candidate claiming one of them - a whole root or a jar nested in a kept one - never
 * enters the resolver, because the resolver refuses a set holding two providers of one id before anything downstream
 * could drop the losing copy, and the instance's own copy is the one that loads.
 * </p>
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

		/** Every id this candidate occupies in a resolver's eyes: its own and the ones it provides. */
		Set<String> ids(T candidate);
	}

	/**
	 * Keeps the request's projection roots and the instance's own mods, each with its whole nested subtree, in discovery
	 * order. A root outside the projection always stays; a root inside it stays only when the request selected it and
	 * the instance does not already claim its ids. The instance's own mods are walked first and claim nothing, since
	 * they are the claim: their nested jars carry ids the instance provides by definition.
	 */
	public static <T> List<T> keep(Collection<T> candidates, Accessor<T> accessor, ModpackLoadRequest request, Set<String> claimedIds) {
		Set<T> discovered = Collections.newSetFromMap(new IdentityHashMap<>());
		discovered.addAll(candidates);
		Set<T> kept = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<T> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<T> queue = new ArrayDeque<>();

		for (T candidate : candidates) if (accessor.isRoot(candidate) && !inProjection(candidate, accessor, request)) queue.add(candidate);
		walk(queue, accessor, discovered, kept, visited, null);

		for (T candidate : candidates) {
			if (!accessor.isRoot(candidate) || !inProjection(candidate, accessor, request)) continue;
			List<Path> paths = accessor.paths(candidate);
			if (paths == null || paths.isEmpty() || !request.allowsProjectionJar(paths.get(0))) continue;
			if (intersects(accessor.ids(candidate), claimedIds)) continue;
			queue.add(candidate);
		}
		walk(queue, accessor, discovered, kept, visited, claimedIds);

		return candidates.stream().filter(kept::contains).toList();
	}

	/**
	 * Collects one queue to exhaustion. {@code claimedIds} is {@code null} while walking the instance's own mods, whose
	 * ids are the claims themselves; a projection walk skips a child the instance already claims together with the
	 * whole nest under it. A candidate reached twice, or a parent cycle inside one nest, is walked once.
	 */
	private static <T> void walk(Deque<T> queue, Accessor<T> accessor, Set<T> discovered, Set<T> kept, Set<T> visited, Set<String> claimedIds) {
		while (!queue.isEmpty()) {
			T candidate = queue.poll();
			if (!visited.add(candidate)) continue;
			kept.add(candidate);
			Collection<T> nested = accessor.nestedMods(candidate);
			if (nested == null) continue;
			for (T child : nested) {
				if (child == null || !discovered.contains(child)) continue;
				if (claimedIds != null && intersects(accessor.ids(child), claimedIds)) continue;
				queue.add(child);
			}
		}
	}

	private static <T> boolean inProjection(T candidate, Accessor<T> accessor, ModpackLoadRequest request) {
		List<Path> paths = accessor.paths(candidate);
		if (paths == null) return false;
		for (Path path : paths) if (request.isProjectionJar(path)) return true;
		return false;
	}

	private static boolean intersects(Set<String> ids, Set<String> claimedIds) {
		if (ids == null || claimedIds == null || claimedIds.isEmpty()) return false;
		for (String id : ids) if (claimedIds.contains(id)) return true;
		return false;
	}
}
