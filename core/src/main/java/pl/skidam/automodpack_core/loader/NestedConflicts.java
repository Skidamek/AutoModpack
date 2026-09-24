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

import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.utils.JarUtils;
import pl.skidam.automodpack_core.utils.SemanticVersion;

/**
 * Loader-agnostic nested-conflict detection. Loader resolution keeps exactly one candidate per mod id, preferring
 * roots over nested jars, so a pack-nested jar only reaches the game when it becomes part of a physical root copy:
 * it is copied out when it wins an id the pack does not ship as a root mod and some standard root nests that id at
 * a strictly older version. An emitted winner also drags the pool's providers of the dependencies of everything it
 * ships: each dependency id nothing already provides is resolved against the pack roots and nested jars, the
 * provider is emitted with the same colliders, and its own dependencies - including those of its whole nest tree,
 * which rides along inside the same bundle entry and is resolved by the loader at boot - are resolved after it, so
 * a provider left unselected would boot the game with a missing dependency. The same mechanics serve the reverse
 * direction: a surviving standard root whose hard dependency id is provided only by the pack gets the provider
 * copied out too - a nested jar, or the pack root itself, which is the pack's authoritative mod and beats a nested
 * provider of the same id. A pack root whose logical path is a force-copy service path is treated as will-be-provided
 * instead: the loader forces that root into the mods directory anyway. Every emitted jar reports the bundle entry
 * name it lands under - the pack root's logical path followed by its entry chain, unique per jar by construction -
 * plus a source that lazily opens its bytes from the pack root's own jar, so the caller can hash and store them.
 * Pure: everything here works on inspected trees, never on the filesystem.
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

	/**
	 * One pack-nested jar to copy into the generated bundle: its inspected mod, the bundle entry name it lands
	 * under, the lazily opened source of its bytes, and the standard roots whose survival requires it.
	 */
	public record Candidate(FileInspection.Mod mod, String entryName, GeneratedBundle.Source source, List<Collider> colliders) {
		public Candidate {
			colliders = List.copyOf(colliders);
		}
	}

	/** A standard root jar: its game-directory logical path plus its inspected tree of nested mods. */
	public record StandardRoot(String logicalPath, FileInspection.Mod mod) {}

	/** One materialized pack root jar: the game-directory logical path it ships at plus its inspected tree rooted at its readable jar. */
	public record PackRoot(String logicalPath, FileInspection.Mod tree) {}

	/**
	 * One node of a pack jar's nesting tree: the inspected jar, its raw entry chain from the root jar (one zip
	 * entry path per nesting level, empty for the root itself), and the bundle entry name it would land under.
	 */
	private static final class Node {
		final FileInspection.Mod mod;
		final Node parent;
		final List<String> chain;
		final String entryName;
		final List<Node> children = new ArrayList<>();

		Node(FileInspection.Mod mod, Node parent, String logicalPath) {
			this.mod = mod;
			this.parent = parent;
			this.chain = parent == null ? List.of() : levels(mod.path());
			this.entryName = chain.isEmpty() ? logicalPath : logicalPath + "/" + LogicalPath.normalize(String.join("/", chain));
		}

		/** Splits a nested mod's virtual chain into one raw zip entry path per nesting level: every level ends at a jar. */
		private static List<String> levels(Path virtualPath) {
			List<String> levels = new ArrayList<>();
			StringBuilder current = new StringBuilder();
			for (Path component : virtualPath) {
				if (current.length() > 0) current.append('/');
				current.append(component);
				if (JarUtils.hasJarExtension(String.valueOf(component))) {
					levels.add(current.toString());
					current.setLength(0);
				}
			}
			return levels;
		}
	}

	/**
	 * The copies the pack needs. Per nested id one winning jar (highest version, ties broken by the smaller
	 * entry name); each winner with a colliding standard root is emitted - unless another emitted jar
	 * already claims one of its ids, which would put two jars declaring that id into one bundle - and every emitted
	 * jar drags the pool's provider of each unprovided declared dependency of everything it ships, recursively, with
	 * the emitter's colliders. A jar sharing an id with an emitted jar or a pack root is never dragged or re-emitted:
	 * the loader's solver selects one whole candidate jar per id and discards the loser completely, so a jar that
	 * loses a shared id takes its every other id out of the bundle with it. Afterwards, a standard root whose hard dependency id
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
		// The managed bundle is never scanned as a standard root: its stale nests would phantom-collide a fresh selection against the bundle itself one generation past their last dependent.
		standardRoots = standardRoots.stream().filter(root -> !ModpackPathPolicy.isGeneratedBundlePath(root.logicalPath())).toList();
		List<Node> roots = new ArrayList<>();
		for (PackRoot packRoot : packRoots) {
			if (packRoot.tree().path() == null) continue;
			roots.add(buildNode(packRoot.tree(), null, packRoot.logicalPath()));
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
		winners.sort(Comparator.comparing(node -> node.entryName));
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
		emission.candidates.sort(Comparator.comparing(candidate -> candidate.entryName()));
		return List.copyOf(emission.candidates);
	}

	private static Node buildNode(FileInspection.Mod mod, Node parent, String logicalPath) {
		Node node = new Node(mod, parent, logicalPath);
		for (String id : mod.IDs())
			if (GeneratedBundle.MOD_ID.equalsIgnoreCase(id))
				throw new IllegalArgumentException((parent == null ? "Pack root " : "Nested jar ") + node.entryName + " claims the reserved generated-bundle id " + GeneratedBundle.MOD_ID);
		List<FileInspection.Mod> nested = new ArrayList<>(mod.nestedMods());
		nested.sort(Comparator.comparing(child -> child.path() == null ? "" : LogicalPath.normalize(child.path().toString())));
		for (FileInspection.Mod child : nested) {
			if (child.path() == null) continue;
			node.children.add(buildNode(child, node, logicalPath));
		}
		return node;
	}

	private static void collectNested(Node node, List<Node> into) {
		into.addAll(node.children);
		for (Node child : node.children) collectNested(child, into);
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
		private final Set<String> emittedIds = new HashSet<>();
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
			return providedByStandards.contains(id) || providedByForceCopy.contains(id) || emittedIds.contains(id);
		}

		/** Records {@code node} as an emitted candidate carrying {@code colliders} and takes its ids. */
		private void emit(Node node, List<Collider> colliders) {
			FileInspection.Mod mod = node.mod;
			candidates.add(new Candidate(mod, node.entryName, source(node), colliders));
			for (String id : mod.IDs()) {
				String normalized = id.toLowerCase(Locale.ROOT);
				claimed.add(normalized);
				emittedIds.add(normalized);
			}
			emitted.add(node);
		}

		/**
		 * Drags the transitive closure of the dependencies of everything {@code node} ships: per unprovided id of
		 * {@code node} or any jar nested inside it the best provider from the pool is emitted with {@code colliders}
		 * and resolved recursively. The nests ride along inside the emitted jar's bundle entry and the loader
		 * resolves them at boot, so their dependencies are the copy's to provide. Nothing outside the generated
		 * bundle provides a covered pack id, so the pack root shipping it is dragged itself.
		 */
		private void dragDependencies(Node node, List<Collider> colliders) {
			for (Node member : subtree(node)) {
				Set<String> dependencyIds = new TreeSet<>();
				for (String dependency : member.mod.deps()) dependencyIds.add(dependency.toLowerCase(Locale.ROOT));
				for (String dependencyId : dependencyIds) {
					if (provided(dependencyId)) continue;
					Node provider = provider(dependencyId);
					if (provider == null) continue;
					emit(provider, colliders);
					dragDependencies(provider, colliders);
				}
			}
		}

		private static List<Node> subtree(Node node) {
			List<Node> members = new ArrayList<>();
			members.add(node);
			for (Node child : node.children) members.addAll(subtree(child));
			return members;
		}

		/** Where an emitted jar's bytes come from: the pack root's own jar, or a chain of entries into it. */
		private static GeneratedBundle.Source source(Node node) {
			Node root = node;
			while (root.parent != null) root = root.parent;
			Path rootJar = root.mod.path();
			return node.chain.isEmpty() ? GeneratedBundle.source(rootJar) : () -> JarUtils.openNestedJar(rootJar, node.chain);
		}

		/**
		 * The best pack jar providing {@code dependencyId}: a pack root beats a nested jar, then {@link #winsJar} applies; an emitted jar never serves again, a nested jar sharing any claimed id never serves, and a pack
		 * root sharing an id an emission already took never serves either.
		 */
		private Node provider(String dependencyId) {
			Node provider = null;
			for (Node node : pool) {
				FileInspection.Mod mod = node.mod;
				if (emitted.contains(node)) continue;
				// A jar sharing an id an emission already took never serves again; a nested jar sharing any covered pack id never serves either.
				if (mod.IDs().stream().anyMatch(id -> emittedIds.contains(id.toLowerCase(Locale.ROOT)))) continue;
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
					if (emittedIds.contains(id)) {
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
				candidates.set(index, new Candidate(candidate.mod(), candidate.entryName(), candidate.source(), colliders));
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
			if (!forceCopyPaths.contains(packRoot.entryName.toLowerCase(Locale.ROOT))) continue;
			FileInspection.Mod mod = packRoot.mod;
			for (String id : mod.IDs()) provided.add(id.toLowerCase(Locale.ROOT));
			collectIds(mod, provided);
		}
		return provided;
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

	/** Winner order between two jars competing for one id: higher version wins, a lexicographically smaller entry name breaks ties. */
	private static boolean winsJar(Node challenger, Node current) {
		return SemanticVersion.wins(challenger.mod.version(), challenger.entryName, current.mod.version(), current.entryName);
	}
}
