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
import java.util.TreeSet;

import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.SemanticVersion;

/**
 * Loader-agnostic nested-conflict detection. Loader resolution keeps exactly one candidate per mod id, preferring
 * roots over nested jars, so a pack-nested jar only reaches the game when it becomes a physical root copy itself:
 * it is copied out when it wins an id the pack does not ship as a root mod and some standard root nests that id at
 * a strictly older version. An emitted winner also drags the transitive closure of its declared-dependency
 * siblings - jars nested beside it in the same parent: the copy is a root in the native pass, which only sees
 * {@code mods/}, so a sibling left nested inside its projection jar would boot the game with a missing dependency.
 * Every emitted jar is reported at the path where the extractor materialized it, derived from the parent chain of
 * entry paths, so the caller can hash and store the bytes. Pure: everything here works on inspected trees, never
 * on the filesystem.
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

	/** One materialized pack root jar: its inspected tree plus the directory where its nested jars were extracted. */
	public record PackRoot(FileInspection.Mod tree, Path extractionBase) {}

	/**
	 * One node of a pack jar's nesting tree: the inspected jar, where the extractor materialized it, and its
	 * position between the pack root and the jars nested inside it.
	 */
	private static final class Node {
		final FileInspection.Mod mod;
		final Path extractedPath;
		final Node parent;
		final List<Node> children = new ArrayList<>();

		Node(FileInspection.Mod mod, Path extractedPath, Node parent) {
			this.mod = mod;
			this.extractedPath = extractedPath;
			this.parent = parent;
		}
	}

	/**
	 * The copies the pack needs. Per nested id one winning jar (highest version, ties broken by the smaller
	 * materialized path); each winner with a colliding standard root is emitted, and every winner drags its
	 * dependency siblings unless their own ids are already covered by an emitted jar or a pack root - a dragged
	 * jar sharing an id with either would make the loader's per-id solver drop the owner. Jars sharing any id
	 * with a pack root mod are never candidates at all, for the same reason.
	 */
	public static List<Candidate> detect(List<PackRoot> packRoots, List<StandardRoot> standardRoots, Set<String> packRootIds) {
		List<Node> roots = new ArrayList<>();
		for (PackRoot packRoot : packRoots) if (packRoot.tree().path() != null) roots.add(buildNode(packRoot.tree(), packRoot.extractionBase(), null, packRoot.extractionBase()));
		List<Node> nested = new ArrayList<>();
		for (Node root : roots) collectNested(root, nested);
		Set<String> coveredIds = new HashSet<>();
		for (String id : packRootIds) coveredIds.add(id.toLowerCase(Locale.ROOT));
		Map<String, Node> winnerById = new HashMap<>();
		for (Node node : nested) {
			FileInspection.Mod mod = node.mod;
			if (mod.IDs().stream().anyMatch(id -> coveredIds.contains(id.toLowerCase(Locale.ROOT)))) continue;
			for (String id : mod.IDs())
				winnerById.merge(id.toLowerCase(Locale.ROOT), node, (current, challenger) -> winsJar(challenger, current) ? challenger : current);
		}
		List<Node> winners = new ArrayList<>(new LinkedHashSet<>(winnerById.values()));
		winners.sort(Comparator.comparing(node -> node.extractedPath.toString()));
		Set<String> claimed = new HashSet<>(coveredIds);
		List<Candidate> candidates = new ArrayList<>();
		for (Node winner : winners) {
			List<Collider> colliders = new ArrayList<>();
			for (StandardRoot root : standardRoots)
				if (root.mod().hash() != null && nestsBeatenVersion(root.mod(), winner.mod)) colliders.add(new Collider(root.logicalPath(), root.mod().hash()));
			if (colliders.isEmpty()) continue;
			candidates.add(new Candidate(winner.mod.at(winner.extractedPath), colliders));
			FileInspection.Mod winnerMod = winner.mod;
			for (String id : winnerMod.IDs()) claimed.add(id.toLowerCase(Locale.ROOT));
			collectDependencySiblings(winner, claimed, candidates, colliders);
		}
		candidates.sort(Comparator.comparing(candidate -> candidate.mod().path().toString()));
		return List.copyOf(candidates);
	}

	private static Node buildNode(FileInspection.Mod mod, Path extractedPath, Node parent, Path extractionBase) {
		Node node = new Node(mod, extractedPath, parent);
		List<FileInspection.Mod> nested = new ArrayList<>(mod.nestedMods());
		nested.sort(Comparator.comparing(child -> child.path() == null ? "" : child.path().toString()));
		for (FileInspection.Mod child : nested) {
			if (child.path() == null) continue;
			// The extractor flattens every depth under the root's own prefix, so a jar materializes at the root base plus its entry path.
			node.children.add(buildNode(child, extractedPath(extractionBase, child.path()), node, extractionBase));
		}
		return node;
	}

	private static void collectNested(Node node, List<Node> into) {
		into.addAll(node.children);
		for (Node child : node.children) collectNested(child, into);
	}

	/** Where the extractor materialized a nested jar: its parent's path followed by the entry path inside the parent. */
	private static Path extractedPath(Path parentPath, Path entryPath) {
		String relative = entryPath.toString();
		while (relative.startsWith("/")) relative = relative.substring(1);
		return parentPath.resolve(relative);
	}

	/**
	 * Drags the transitive closure of {@code node}'s declared-dependency siblings: per dependency id the best
	 * providing sibling, and then that sibling's own dependencies, each only while entirely unclaimed. Jars
	 * nested inside {@code node} itself are skipped - they travel with the copy and resolve natively.
	 */
	private static void collectDependencySiblings(Node node, Set<String> claimed, List<Candidate> candidates, List<Collider> colliders) {
		if (node.parent == null || node.mod.deps().isEmpty()) return;
		Set<String> dependencyIds = new TreeSet<>();
		for (String dependency : node.mod.deps()) dependencyIds.add(dependency.toLowerCase(Locale.ROOT));
		List<Node> drags = new ArrayList<>();
		for (String dependencyId : dependencyIds) {
			Node provider = null;
			for (Node sibling : node.parent.children) {
				if (sibling == node) continue;
				if (!provides(sibling.mod, dependencyId)) continue;
				if (provider == null || winsJar(sibling, provider)) provider = sibling;
			}
			if (provider != null && !drags.contains(provider)) drags.add(provider);
		}
		for (Node drag : drags) {
			FileInspection.Mod dragMod = drag.mod;
			if (dragMod.IDs().stream().anyMatch(id -> claimed.contains(id.toLowerCase(Locale.ROOT)))) continue;
			candidates.add(new Candidate(dragMod.at(drag.extractedPath), colliders));
			for (String id : dragMod.IDs()) claimed.add(id.toLowerCase(Locale.ROOT));
			collectDependencySiblings(drag, claimed, candidates, colliders);
		}
	}

	private static boolean provides(FileInspection.Mod mod, String dependencyId) {
		return mod.IDs().stream().anyMatch(id -> id.equalsIgnoreCase(dependencyId));
	}

	/**
	 * Whether {@code root} nests any id of {@code winner} at a version the winner strictly beats; per id its newest
	 * nested jar speaks for the root, and an unparseable nested version is never beaten - the winner can only be
	 * proven newer against a parseable one.
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

	/**
	 * The mods-directory file name a copy lands at: the original nested jar's name when free among the taken names,
	 * otherwise the stem suffixed with the SHA-1 prefix. Deterministic, so repeated plans choose the same name.
	 */
	public static String landingName(String originalName, String sha1, Set<String> takenNames) {
		if (!takenNames.contains(originalName)) return originalName;
		int dot = originalName.lastIndexOf('.');
		String stem = dot <= 0 ? originalName : originalName.substring(0, dot);
		String extension = dot <= 0 ? "" : originalName.substring(dot);
		return stem + "-" + sha1.substring(0, 8) + extension;
	}
}
