package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RequestedCandidatesTest {
	private static final Path INSTANCE_MODS = Path.of("/instance/mods");
	private static final Path PROJECTION_MODS = Path.of("/pack/mods");

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

		@Override
		public Set<String> ids(Node candidate) {
			Set<String> ids = new HashSet<>(candidate.provides);
			ids.add(candidate.id);
			return ids;
		}
	};

	private static Node root(String id, Path directory, Node... nested) {
		return new Node(id, true, List.of(directory.resolve(id + ".jar")), List.of(), List.of(nested));
	}

	private static Node nested(String id, Node... nested) {
		return new Node(id, false, List.of(), List.of(), List.of(nested));
	}

	private static Node nestedProviding(String id, String provided, Node... nested) {
		return new Node(id, false, List.of(), List.of(provided), List.of(nested));
	}

	private static ModpackLoadRequest request(Node... requested) {
		return new ModpackLoadRequest(PROJECTION_MODS, Arrays.stream(requested).flatMap(node -> node.paths.stream()).toList());
	}

	private static List<String> ids(Collection<Node> candidates) {
		return candidates.stream().map(node -> node.id).toList();
	}

	@Test
	void keepsTheWholeNestedSubtreeOfRequestedRoots() {
		Node yumiCommonsCore = nested("yumi_commons_core");
		Node yumiEvent = nested("yumi_commons_event");
		Node yumiCore = nested("yumi_mc_core", yumiEvent, yumiCommonsCore);
		Node trinkets = root("trinkets_updated", PROJECTION_MODS, yumiCore);

		List<Node> kept = RequestedCandidates.keep(List.of(trinkets, yumiCore, yumiEvent, yumiCommonsCore), ACCESSOR, request(trinkets), Set.of());

		assertEquals(Set.of("trinkets_updated", "yumi_mc_core", "yumi_commons_core", "yumi_commons_event"), new HashSet<>(ids(kept)));
	}

	@Test
	void doesNotInventNestedCandidatesTheDiscovererAlreadyDropped() {
		Node envDisabled = nested("client_only_nested");
		Node requested = root("requested", PROJECTION_MODS, envDisabled);

		List<Node> kept = RequestedCandidates.keep(List.of(requested), ACCESSOR, request(requested), Set.of());

		assertEquals(List.of("requested"), ids(kept));
	}

	@Test
	void dropsUnrequestedRootsWithTheirWholeSubtree() {
		Node pinnedNested = nested("pinned_nested");
		Node pinnedProjectionCopy = root("pinned", PROJECTION_MODS, pinnedNested);
		Node requestedNested = nested("requested_nested");
		Node requested = root("requested", PROJECTION_MODS, requestedNested);

		List<Node> kept = RequestedCandidates.keep(List.of(pinnedProjectionCopy, pinnedNested, requested, requestedNested), ACCESSOR, request(requested), Set.of());

		assertEquals(List.of("requested", "requested_nested"), ids(kept));
	}

	@Test
	void keepsANestedModAlsoNestedUnderADroppedRoot() {
		Node shared = nested("shared");
		Node dropped = root("dropped", PROJECTION_MODS, shared);
		Node requested = root("requested", PROJECTION_MODS, shared);

		List<Node> kept = RequestedCandidates.keep(List.of(dropped, requested, shared), ACCESSOR, request(requested), Set.of());

		assertEquals(List.of("requested", "shared"), ids(kept));
	}

	@Test
	void keepsPathlessRoots() {
		Node builtin = new Node("java", true, List.of(), List.of(), List.of());

		assertEquals(List.of("java"), ids(RequestedCandidates.keep(List.of(builtin), ACCESSOR, request(), Set.of())));
	}

	/** The instance's own mods are the claim, so their nesting must survive even though it carries the claimed ids. */
	@Test
	void keepsTheInstanceCopiesOfTheIdsItProvides() {
		Node kotlinStdlib = nested("kotlin_stdlib");
		Node instanceKotlin = root("fabric-language-kotlin", INSTANCE_MODS, kotlinStdlib);

		List<Node> kept = RequestedCandidates.keep(List.of(instanceKotlin, kotlinStdlib), ACCESSOR, request(), Set.of("fabric-language-kotlin", "kotlin_stdlib"));

		assertEquals(List.of("fabric-language-kotlin", "kotlin_stdlib"), ids(kept));
	}

	@Test
	void dropsTheProjectionRootTheInstanceAlreadyProvides() {
		Node packStdlib = nested("pack_kotlin_stdlib");
		Node packKotlin = root("fabric-language-kotlin", PROJECTION_MODS, packStdlib);
		Node requested = root("requested", PROJECTION_MODS);

		List<Node> kept = RequestedCandidates.keep(List.of(packKotlin, packStdlib, requested), ACCESSOR, request(packKotlin, requested), Set.of("fabric-language-kotlin"));

		assertEquals(List.of("requested"), ids(kept));
	}

	@Test
	void dropsTheNestedCopyProvidingAnIdTheInstanceAlreadyProvides() {
		Node sidecarStdlib = nested("sidecar_stdlib");
		Node nestedKotlin = nestedProviding("kotlin_sidecar", "fabric-language-kotlin", sidecarStdlib);
		Node ownLibrary = nested("own_library");
		Node carrier = root("carrier", PROJECTION_MODS, nestedKotlin, ownLibrary);

		List<Node> kept = RequestedCandidates.keep(List.of(carrier, nestedKotlin, sidecarStdlib, ownLibrary), ACCESSOR, request(carrier), Set.of("fabric-language-kotlin"));

		assertEquals(List.of("carrier", "own_library"), ids(kept));
	}

	private record Node(String id, boolean root, List<Path> paths, List<String> provides, Collection<Node> nested) {
		Node {
			paths = List.copyOf(paths);
			provides = List.copyOf(provides);
			nested = List.copyOf(nested);
		}
	}
}
