package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.SemanticVersion;

/**
 * Loader-agnostic nested-conflict detection. Loader resolution keeps exactly one candidate per mod id, preferring
 * roots over nested jars, so a pack-nested jar only reaches the game when it becomes part of a physical root copy:
 * it is copied out when it wins an id the pack does not ship as a root mod and some standard root nests that id at
 * a strictly older version. An emitted winner also drags the pool's providers of its declared dependencies: each
 * dependency id nothing already provides is resolved against the pack roots and nested jars, the provider is
 * emitted with the same colliders, and its own dependencies are resolved after it - the copy lives inside the
 * generated bundle, which only carries the selected jars, so a provider left unselected would boot the game with a
 * missing dependency. The same mechanics serve the reverse direction: a surviving standard root whose hard
 * dependency id is provided only by the pack gets the provider copied out too - a nested jar, or the pack root
 * itself, which is the pack's authoritative mod and beats a nested provider of the same id. A pack root whose
 * logical path is a force-copy service path is treated as will-be-provided instead: the loader forces that root
 * into the mods directory anyway. Every emitted jar is reported at the path where the extractor materialized it
 * (the pack root's own inspection copy for a root provider), so the caller can hash and store the bytes. Pure:
 * everything here works on inspected trees, never on the filesystem.
 */
public final class NestedConflicts {

	private NestedConflicts() {}

	/** A standard root jar whose nested candidate loses to the pack, identified by logical path and observed content. */
	public record Collider(String logicalPath, String sha1) {
		public Collider {
			logicalPath = logicalPath.toLowerCase(Locale.ROOT);
			sha1 = sha1 == null ? null : sha1.toLowerCase(Locale.ROOT);
		}
	}

	/** One pack-nested jar to copy into the standard mods directory, with the standard roots whose survival requires it. */
	public record Candidate(FileInspection.Mod mod, List<Collider> colliders) {
		public Candidate {
			colliders = List.copyOf(colliders);
		}
	}

	/** A standard root jar: its game-directory logical path plus its inspected tree of nested mods. */
	public record StandardRoot(String logicalPath, FileInspection.Mod mod) {}

	/** One materialized pack root jar: the game-directory logical path it ships at, its inspected tree, and the directory where its nested jars were extracted. */
	public record PackRoot(String logicalPath, FileInspection.Mod tree, Path extractionBase) {}

	/**
	 * One node of a pack jar's nesting tree: the inspected jar, where the extractor materialized it, its
	 * position between the pack root and the jars nested inside it, and for a pack root the logical path it
	 * ships at ({@code null} for nested jars).
	 */
	private static final class Node {
		final FileInspection.Mod mod;
		final Path extractedPath;
		final Node parent;
		final String logicalPath;
		final List<Node> children = new ArrayList<>();

		Node(FileInspection.Mod mod, Path extractedPath, Node parent, String logicalPath) {
			this.mod = mod;
			this.extractedPath = extractedPath;
			this.parent = parent;
			this.logicalPath = logicalPath;
		}
	}

	/**
	 * The copies the pack needs. Per nested id one winning jar (highest version, ties broken by the smaller
	 * materialized path); each winner with a colliding standard root is emitted - unless another emitted jar
	 * already claims one of its ids, which would put two jars declaring that id into one bundle - and every emitted
	 * jar drags the pool's provider of each unprovided declared dependency, recursively, with the emitter's
	 * colliders. A jar sharing an id with an emitted jar or a pack root is never dragged or re-emitted - either
	 * would make the loader's per-id solver drop the owner. Afterwards, a standard root whose hard dependency id
	 * nothing in the standard mods directory provides gets the best provider from the pack copied out - a pack root
	 * preferred over a nested jar - colliding with the dependent roots so the copy lives and dies with their
	 * survival under the plan. A dependency provided by a force-copy pack root is left alone: that root lands in
	 * the standard mods directory on its own. A previously generated copy must not count as provision for its own
	 * dependency - it would satisfy the scan, get retired, and resurrect the crash one launch later - so
	 * {@code previouslyCopiedPaths} are skipped when the provision set is collected.
	 */
	public static List<Candidate> detect(List<PackRoot> packRoots, List<StandardRoot> standardRoots, Set<String> packRootIds) {
		return detect(packRoots, standardRoots, packRootIds, Set.of(), Set.of());
	}

	public static List<Candidate> detect(List<PackRoot> packRoots, List<StandardRoot> standardRoots, Set<String> packRootIds, Set<String> previouslyCopiedPaths, Set<String> forceCopyPaths) {
		List<Node> roots = new ArrayList<>();
		for (PackRoot packRoot : packRoots) {
			if (packRoot.tree().path() == null) continue;
			for (String id : packRoot.tree().IDs())
				if (GeneratedBundle.MOD_ID.equalsIgnoreCase(id))
					throw new IllegalArgumentException("Pack root " + packRoot.tree().path().getFileName() + " claims the reserved generated-bundle id " + GeneratedBundle.MOD_ID);
			roots.add(buildNode(packRoot.tree(), packRoot.extractionBase(), null, packRoot.logicalPath(), packRoot.extractionBase()));
		}
		List<Node> nested = new ArrayList<>();
		for (Node root : roots) collectNested(root, nested);
		Set<String> coveredIds = new HashSet<>();
		for (String id : packRootIds) coveredIds.add(id.toLowerCase(Locale.ROOT));
		Set<String> forceCopy = new HashSet<>();
		for (String path : forceCopyPaths) forceCopy.add(path.toLowerCase(Locale.ROOT));
		Emission emission = new Emission(roots, nested, coveredIds, providedByStandards(standardRoots, previouslyCopiedPaths), providedByForceCopy(roots, forceCopy));
		Map<String, Node> winnerById = new HashMap<>();
		for (Node node : nested) {
			FileInspection.Mod mod = node.mod;
			if (mod.IDs().stream().anyMatch(id -> coveredIds.contains(id.toLowerCase(Locale.ROOT)))) continue;
			for (String id : mod.IDs())
				winnerById.merge(id.toLowerCase(Locale.ROOT), node, (current, challenger) -> winsJar(challenger, current) ? challenger : current);
		}
		List<Node> winners = new ArrayList<>(new LinkedHashSet<>(winnerById.values()));
		winners.sort(Comparator.comparing(node -> node.extractedPath.toString()));
		for (Node winner : winners) {
			FileInspection.Mod winnerMod = winner.mod;
			List<Collider> colliders = new ArrayList<>();
			for (StandardRoot root : standardRoots)
				if (root.mod().hash() != null && nestsBeatenVersion(root.mod(), winnerMod)) colliders.add(new Collider(root.logicalPath(), root.mod().hash()));
			if (colliders.isEmpty()) continue;
			if (winnerMod.IDs().stream().anyMatch(id -> emission.claimed.contains(id.toLowerCase(Locale.ROOT)))) continue;
			emission.emit(winner, colliders);
			emission.dragDependencies(winner, colliders);
		}
		emission.emitDependencyDrivenCopies(standardRoots);
		emission.candidates.sort(Comparator.comparing(candidate -> candidate.mod().path().toString()));
		return List.copyOf(emission.candidates);
	}

	private static Node buildNode(FileInspection.Mod mod, Path extractedPath, Node parent, String logicalPath, Path extractionBase) {
		Node node = new Node(mod, extractedPath, parent, logicalPath);
		List<FileInspection.Mod> nested = new ArrayList<>(mod.nestedMods());
		nested.sort(Comparator.comparing(child -> child.path() == null ? "" : child.path().toString()));
		for (FileInspection.Mod child : nested) {
			if (child.path() == null) continue;
			// The extractor flattens every depth under the root's own prefix, so a jar materializes at the root base plus its entry path.
			node.children.add(buildNode(child, extractedPath(extractionBase, child.path()), node, null, extractionBase));
		}
		return node;
	}

	private static void collectNested(Node node, List<Node> into) {
		into.addAll(node.children);
		for (Node child : node.children) collectNested(child, into);
	}

	/**
	 * Where the extractor materialized a nested jar: its parent's path followed by the entry path inside the parent. The leading root separator is stripped for both separator styles - on Windows a path with a root would
	 * make {@link Path#resolve} drop the parent entirely.
	 */
	private static Path extractedPath(Path parentPath, Path entryPath) {
		String relative = entryPath.toString();
		while (!relative.isEmpty() && (relative.charAt(0) == '/' || relative.charAt(0) == '\\')) relative = relative.substring(1);
		return parentPath.resolve(relative);
	}

	/**
	 * One detect run's shared emission state: the provider pool, what the standard mods directory already
	 * provides, and the ids and jars already taken by an emission. {@code claimed} holds the covered pack ids plus
	 * every emitted jar's ids and doubles as the drag recursion's cycle guard; {@code emitted} keeps an emitted
	 * jar from serving as a provider twice.
	 */
	private static final class Emission {
		private final List<Node> pool = new ArrayList<>();
		private final Set<String> coveredIds;
		private final Set<String> providedByStandards;
		private final Set<String> providedByForceCopy;
		private final Set<String> claimed;
		private final Set<Node> emitted = new HashSet<>();
		private final List<Candidate> candidates = new ArrayList<>();

		Emission(List<Node> packRootNodes, List<Node> nested, Set<String> coveredIds, Set<String> providedByStandards, Set<String> providedByForceCopy) {
			pool.addAll(packRootNodes);
			pool.addAll(nested);
			this.coveredIds = coveredIds;
			this.providedByStandards = providedByStandards;
			this.providedByForceCopy = providedByForceCopy;
			claimed = new HashSet<>(coveredIds);
		}

		/** Whether {@code id} is provided once the emitted jars land: by a scanned standard root, a force-copy pack root, or an already-emitted jar. A covered pack id is not - the pack root shipping it serves then. */
		private boolean provided(String id) {
			return providedByStandards.contains(id) || providedByForceCopy.contains(id) || (claimed.contains(id) && !coveredIds.contains(id));
		}

		/** Whether {@code id} is taken by an emitted jar, as opposed to a covered pack id. */
		private boolean emittedId(String id) {
			return claimed.contains(id) && !coveredIds.contains(id);
		}

		/** Records {@code node} as an emitted candidate carrying {@code colliders} and takes its ids. */
		private void emit(Node node, List<Collider> colliders) {
			FileInspection.Mod mod = node.mod;
			candidates.add(new Candidate(mod.at(sourcePath(node)), colliders));
			for (String id : mod.IDs()) claimed.add(id.toLowerCase(Locale.ROOT));
			emitted.add(node);
		}

		/**
		 * Drags the transitive closure of {@code node}'s declared dependencies: per unprovided id the best provider
		 * from the pool is emitted with {@code colliders} and resolved recursively. Nothing outside the generated
		 * bundle provides a covered pack id, so the pack root shipping it is dragged itself.
		 */
		private void dragDependencies(Node node, List<Collider> colliders) {
			Set<String> dependencyIds = new TreeSet<>();
			for (String dependency : node.mod.deps()) dependencyIds.add(dependency.toLowerCase(Locale.ROOT));
			for (String dependencyId : dependencyIds) {
				if (provided(dependencyId)) continue;
				Node provider = provider(dependencyId);
				if (provider == null) continue;
				emit(provider, colliders);
				dragDependencies(provider, colliders);
			}
		}

		/** The best pack jar providing {@code dependencyId}: a pack root beats a nested jar, then {@link #winsJar} applies; an emitted jar never serves again and a nested jar sharing any claimed id never serves. */
		private Node provider(String dependencyId) {
			Node provider = null;
			for (Node node : pool) {
				FileInspection.Mod mod = node.mod;
				if (emitted.contains(node)) continue;
				if (node.parent != null && mod.IDs().stream().anyMatch(id -> claimed.contains(id.toLowerCase(Locale.ROOT)))) continue;
				if (!provides(mod, dependencyId)) continue;
				if (provider == null || beatsProvider(node, provider)) provider = node;
			}
			return provider;
		}

		/**
		 * Emits copies for standard-root hard dependencies nothing in sight provides: per unmet dependency id the
		 * best provider from the pack - a pack root beating a nested jar of the same id, since the root is the
		 * pack's authoritative mod - with every dependent root as its collider. The copy then lives and dies with
		 * the dependent roots' survival under the plan, exactly like a collision-driven candidate. A dependency
		 * whose id an emitted jar already claims gains the dependent as an extra collider on that jar instead -
		 * either surviving reason keeps the copy. A dependency provided by a standard root, or by a force-copy pack
		 * root (the loader lands that root in the standard mods directory on its own, nesting included), is
		 * will-be-provided and never triggers a copy.
		 */
		private void emitDependencyDrivenCopies(List<StandardRoot> standardRoots) {
			Map<String, List<StandardRoot>> dependents = new TreeMap<>();
			for (StandardRoot root : standardRoots)
				for (String dependency : root.mod().deps()) {
					String id = dependency.toLowerCase(Locale.ROOT);
					if (providedByStandards.contains(id) || providedByForceCopy.contains(id)) continue;
					if (emittedId(id)) {
						claimDependent(id, root);
						continue;
					}
					dependents.computeIfAbsent(id, key -> new ArrayList<>()).add(root);
				}
			for (var entry : dependents.entrySet()) {
				Node provider = provider(entry.getKey());
				if (provider == null) continue;
				List<Collider> colliders = dependentColliders(entry.getValue());
				if (colliders.isEmpty()) continue;
				emit(provider, colliders);
				dragDependencies(provider, colliders);
			}
		}

		/** Adds {@code root} as a collider on the already-emitted candidate claiming {@code id}, deduplicated by path. */
		private void claimDependent(String id, StandardRoot root) {
			if (root.mod().hash() == null) return;
			for (int index = 0; index < candidates.size(); index++) {
				Candidate candidate = candidates.get(index);
				if (candidate.mod().IDs().stream().noneMatch(candidateId -> candidateId.equalsIgnoreCase(id))) continue;
				List<Collider> colliders = new ArrayList<>(candidate.colliders());
				Collider collider = new Collider(root.logicalPath(), root.mod().hash());
				if (colliders.stream().noneMatch(existing -> existing.logicalPath().equals(collider.logicalPath()))) colliders.add(collider);
				candidates.set(index, new Candidate(candidate.mod(), colliders));
				return;
			}
		}
	}

	/**
	 * The ids the standard mods directory will provide once the plan lands: every scanned root's own and nested ids. Previously generated copies are not provision - counting them would retire the copies the scan exists
	 * to keep.
	 */
	private static Set<String> providedByStandards(List<StandardRoot> standardRoots, Set<String> previouslyCopiedPaths) {
		Set<String> provided = new HashSet<>();
		for (StandardRoot root : standardRoots) {
			if (previouslyCopiedPaths.contains(root.logicalPath())) continue;
			for (String id : root.mod().IDs()) provided.add(id.toLowerCase(Locale.ROOT));
			collectIds(root.mod(), provided);
		}
		return provided;
	}

	/** The ids a force-copy pack root provides from the standard mods directory: the loader lands that root there on its own, nesting included. */
	private static Set<String> providedByForceCopy(List<Node> packRootNodes, Set<String> forceCopyPaths) {
		Set<String> provided = new HashSet<>();
		for (Node packRoot : packRootNodes) {
			if (packRoot.logicalPath == null || !forceCopyPaths.contains(packRoot.logicalPath.toLowerCase(Locale.ROOT))) continue;
			FileInspection.Mod mod = packRoot.mod;
			for (String id : mod.IDs()) provided.add(id.toLowerCase(Locale.ROOT));
			collectIds(mod, provided);
		}
		return provided;
	}

	/** Where a provider's bytes live: a pack root's own materialized inspection jar, or a nested jar's extracted file. */
	private static Path sourcePath(Node provider) {
		return provider.parent == null ? provider.mod.path() : provider.extractedPath;
	}

	private static boolean provides(FileInspection.Mod mod, String dependencyId) {
		return mod.IDs().stream().anyMatch(id -> id.equalsIgnoreCase(dependencyId));
	}

	/**
	 * Provider order between two jars competing to serve one dependency id: a pack root beats a nested jar - it
	 * is the pack's authoritative mod - and within a tier {@link #winsJar} applies.
	 */
	private static boolean beatsProvider(Node challenger, Node current) {
		if ((challenger.parent == null) != (current.parent == null)) return challenger.parent == null;
		return winsJar(challenger, current);
	}

	private static List<Collider> dependentColliders(List<StandardRoot> dependents) {
		List<Collider> colliders = new ArrayList<>();
		for (StandardRoot dependent : dependents)
			if (dependent.mod().hash() != null) colliders.add(new Collider(dependent.logicalPath(), dependent.mod().hash()));
		return colliders;
	}

	private static void collectIds(FileInspection.Mod mod, Set<String> into) {
		for (FileInspection.Mod nested : mod.nestedMods()) {
			for (String id : nested.IDs()) into.add(id.toLowerCase(Locale.ROOT));
			collectIds(nested, into);
		}
	}

	/**
	 * Whether {@code root} nests any id of {@code winner} at a version the winner strictly beats; per id its newest
	 * nested jar speaks for the root. Every non-blank version parses, so a missing version string is the only way a
	 * nested jar escapes comparison.
	 */
	private static boolean nestsBeatenVersion(FileInspection.Mod root, FileInspection.Mod winner) {
		SemanticVersion parsedWinner = SemanticVersion.parseOrNull(winner.version());
		if (parsedWinner == null) return false;
		for (String id : winner.IDs()) {
			SemanticVersion newest = null;
			boolean unparseable = false;
			for (FileInspection.Mod nested : root.nestedMods()) {
				if (nested.IDs().stream().noneMatch(candidate -> candidate.equalsIgnoreCase(id))) continue;
				SemanticVersion parsed = SemanticVersion.parseOrNull(nested.version());
				if (parsed == null) unparseable = true;
				else if (newest == null || parsed.compareTo(newest) > 0) newest = parsed;
			}
			if (!unparseable && newest != null && parsedWinner.compareTo(newest) > 0) return true;
		}
		return false;
	}

	/** Winner order between two jars competing for one id: higher version wins, a lexicographically smaller jar path breaks ties. */
	private static boolean winsJar(Node challenger, Node current) {
		int comparison = SemanticVersion.compareVersionStrings(challenger.mod.version(), current.mod.version());
		if (comparison != 0) return comparison > 0;
		return challenger.extractedPath.toString().compareTo(current.extractedPath.toString()) < 0;
	}
}
