package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class RequestedCandidatesTest {
	private static final RequestedCandidates.Accessor<Node> ACCESSOR = new RequestedCandidates.Accessor<>() {
		@Override
		public boolean isRoot(Node candidate) {
			return candidate.root;
		}

		@Override
		public List<Path> paths(Node candidate) {
			return candidate.paths;
		}

		@Override
		public Collection<Node> nestedMods(Node candidate) {
			return candidate.nested;
		}
	};

	private static Node root(String id, String jar, Node... nested) {
		return new Node(id, true, List.of(Path.of(jar)), List.of(nested));
	}

	private static Node nested(String id, Node... nested) {
		return new Node(id, false, List.of(), List.of(nested));
	}

	private static Set<String> ids(Collection<Node> candidates) {
		return candidates.stream().map(node -> node.id).collect(Collectors.toSet());
	}

	@Test
	void keepsTheWholeNestedSubtreeOfRequestedRoots() {
		Node yumiEvent = nested("yumi_commons_event");
		Node yumiCore = nested("yumi_mc_core", yumiEvent, nested("yumi_commons_core"));
		Node trinkets = root("trinkets_updated", "/pack/mods/trinkets.jar", yumiCore);

		List<Node> kept = RequestedCandidates.keep(List.of(trinkets, yumiCore, yumiEvent), ACCESSOR, path -> true);

		assertEquals(Set.of("trinkets_updated", "yumi_mc_core", "yumi_commons_core", "yumi_commons_event"), ids(kept));
	}

	@Test
	void dropsUnrequestedRootsWithTheirWholeSubtree() {
		Node pinnedNested = nested("pinned_nested");
		Node pinnedProjectionCopy = root("pinned", "/pack/mods/pinned.jar", pinnedNested);
		Node requested = root("requested", "/pack/mods/requested.jar", nested("requested_nested"));

		List<Node> kept = RequestedCandidates.keep(List.of(pinnedProjectionCopy, pinnedNested, requested, nested("requested_nested")), ACCESSOR, path -> path.endsWith("requested.jar"));

		assertEquals(Set.of("requested", "requested_nested"), ids(kept));
	}

	@Test
	void keepsANestedModAlsoNestedUnderADroppedRoot() {
		Node shared = nested("shared");
		Node dropped = root("dropped", "/pack/mods/dropped.jar", shared);
		Node requested = root("requested", "/pack/mods/requested.jar", shared);

		List<Node> kept = RequestedCandidates.keep(List.of(dropped, requested, shared), ACCESSOR, path -> path.endsWith("requested.jar"));

		assertEquals(List.of("requested", "shared"), kept.stream().map(node -> node.id).toList());
	}

	@Test
	void keepsPathlessRoots() {
		Node builtin = new Node("java", true, List.of(), List.of());

		assertEquals(List.of("java"), RequestedCandidates.keep(List.of(builtin), ACCESSOR, path -> false).stream().map(node -> node.id).toList());
	}

	private record Node(String id, boolean root, List<Path> paths, Collection<Node> nested) {}
}
