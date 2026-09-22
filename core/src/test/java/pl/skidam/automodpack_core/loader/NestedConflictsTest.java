package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.loader.NestedConflicts.Candidate;
import pl.skidam.automodpack_core.loader.NestedConflicts.Collider;
import pl.skidam.automodpack_core.loader.NestedConflicts.StandardRoot;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.utils.FileInspection;

class NestedConflictsTest {
	private static final String ROOT_HASH = "1111111111111111111111111111111111111111";

	@Test
	void newerNestedIdBecomesACopyAgainstTheOlderStandardRoot() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/api.jar", "2.0.0", Set.of("fabric-api"), Set.of()));
		StandardRoot standard = standard("mods/local.jar", nested(Set.of("fabric-api"), "1.0.0"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(standard), Set.of());

		assertEquals(1, candidates.size());
		assertEquals(Path.of("nested/pack.jar/META-INF/jars/api.jar"), candidates.get(0).mod().path());
		assertEquals(List.of(new Collider("mods/local.jar", ROOT_HASH)), candidates.get(0).colliders());
	}

	@Test
	void equalOrOlderNestingNeverCopies() {
		StandardRoot equal = standard("mods/local.jar", nested(Set.of("a"), "1.0.0"));
		StandardRoot newer = standard("mods/local.jar", nested(Set.of("a"), "2.0.0"));
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/a.jar", "1.0.0", Set.of("a"), Set.of()));

		assertTrue(NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(equal), Set.of()).isEmpty());
		assertTrue(NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(newer), Set.of()).isEmpty());
	}

	@Test
	void packSideWinnerPerIdPrefersTheHighestVersionThenTheSmallerPath() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/old.jar", "1.0.0", Set.of("a"), Set.of()),
				tree("/META-INF/jars/z.jar", "3.0.0", Set.of("a"), Set.of()),
				tree("/META-INF/jars/b.jar", "3.0.0", Set.of("a"), Set.of()));
		StandardRoot standard = standard("mods/local.jar", nested(Set.of("a"), "2.0.0"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(standard), Set.of());

		assertEquals(1, candidates.size());
		assertEquals(Path.of("nested/pack.jar/META-INF/jars/b.jar"), candidates.get(0).mod().path());
	}

	@Test
	void aJarCoveredByAPackRootModIsNeverCopied() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/shared.jar", "2.0.0", Set.of("a", "b"), Set.of()));
		StandardRoot standardB = standard("mods/local.jar", nested(Set.of("b"), "1.0.0"));

		assertTrue(NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(standardB), Set.of("A")).isEmpty());
		assertEquals(1, NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(standardB), Set.of("other")).size());
	}

	@Test
	void collidersCarryOnlyTheRootsThatNestABeatenVersion() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/winner.jar", "2.0.0", Set.of("a", "b"), Set.of()));
		StandardRoot beatenOnBoth = standard("mods/old.jar", nested(Set.of("a"), "1.0.0"), nested(Set.of("b"), "1.5.0"));
		StandardRoot beatenOnOne = standard("mods/mixed.jar", nested(Set.of("a"), "3.0.0"), nested(Set.of("b"), "1.0.0"));
		StandardRoot unbeaten = standard("mods/new.jar", nested(Set.of("a"), "9.0.0"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(beatenOnBoth, beatenOnOne, unbeaten), Set.of());

		assertEquals(1, candidates.size());
		assertEquals(List.of(new Collider("mods/old.jar", ROOT_HASH), new Collider("mods/mixed.jar", ROOT_HASH)), candidates.get(0).colliders());
	}

	@Test
	void aMissingVersionIsNeverBeatenAndNeverBeats() {
		StandardRoot missing = standard("mods/local.jar", nested(Set.of("a"), null));
		StandardRoot parseable = standard("mods/local.jar", nested(Set.of("a"), "1.0.0"));
		FileInspection.Mod parseablePackRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/a.jar", "2.0.0", Set.of("a"), Set.of()));
		FileInspection.Mod missingPackRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/a.jar", null, Set.of("a"), Set.of()));

		// Only a missing version string escapes comparison: it is never beaten and never proves itself newer.
		assertTrue(NestedConflicts.detect(List.of(packRoot(parseablePackRoot)), List.of(missing), Set.of()).isEmpty());
		assertTrue(NestedConflicts.detect(List.of(packRoot(missingPackRoot)), List.of(parseable), Set.of()).isEmpty());
		assertEquals(1, NestedConflicts.detect(List.of(packRoot(parseablePackRoot)), List.of(parseable), Set.of()).size());
	}

	@Test
	void winnerDragsTheSiblingProvidingADeclaredDependency() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/w.jar", "2.0.0", Set.of("a"), Set.of("sib")),
				tree("/META-INF/jars/sib.jar", "1.0.0", Set.of("sib"), Set.of()));
		StandardRoot standard = standard("mods/local.jar", nested(Set.of("a"), "1.0.0"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(standard), Set.of());

		// The sibling is a per-id winner itself, but with no colliders it is not emitted - it must still be draggable.
		assertEquals(2, candidates.size());
		assertEquals(Path.of("nested/pack.jar/META-INF/jars/sib.jar"), candidates.get(0).mod().path());
		assertEquals(Path.of("nested/pack.jar/META-INF/jars/w.jar"), candidates.get(1).mod().path());
		assertEquals(candidates.get(1).colliders(), candidates.get(0).colliders());
	}

	@Test
	void aDragIsExcludedWhenItsIdIsClaimedByAnotherEmittedWinnerOrAPackRoot() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/w.jar", "2.0.0", Set.of("a"), Set.of("x", "px")),
				tree("/META-INF/jars/e.jar", "2.0.0", Set.of("x"), Set.of()),
				tree("/META-INF/jars/old-x.jar", "1.0.0", Set.of("x"), Set.of()),
				tree("/META-INF/jars/px.jar", "1.0.0", Set.of("px"), Set.of()));
		StandardRoot standard = standard("mods/local.jar", nested(Set.of("a"), "1.0.0"), nested(Set.of("x"), "1.0.0"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(standard), Set.of("PX"));

		// The winner and the emitted x-winner are copied; both dependency providers are excluded: old-x.jar
		// because the emitted winner already claims x, px.jar because a pack root mod claims px.
		assertEquals(List.of("nested/pack.jar/META-INF/jars/e.jar", "nested/pack.jar/META-INF/jars/w.jar"), paths(candidates));
	}

	@Test
	void aWinnerSharingAnIdWithAnAlreadyEmittedWinnerIsNotEmittedAgain() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/a.jar", "2.0.0", Set.of("x"), Set.of()),
				tree("/META-INF/jars/b.jar", "1.0.0", Set.of("x", "y"), Set.of()));
		StandardRoot standard = standard("mods/local.jar", nested(Set.of("x"), "1.0.0"), nested(Set.of("y"), "0.5.0"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(standard), Set.of());

		// Both jars win an id and both collide with the root, but emitting both would put two jars declaring x into one bundle: b loses its shared id to a and stays out.
		assertEquals(1, candidates.size());
		assertEquals(Path.of("nested/pack.jar/META-INF/jars/a.jar"), candidates.get(0).mod().path());
	}

	@Test
	void aBundledPackRootDragsAnotherPackRootProvidingItsDependency() {
		FileInspection.Mod rootA = tree("a.jar", "1.0.0", Set.of("a"), Set.of("b"));
		FileInspection.Mod rootB = tree("b.jar", "1.0.0", Set.of("b"), Set.of());
		StandardRoot dependent = standard("mods/one.jar", Set.of("one"), Set.of("a"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot("mods/a.jar", rootA), packRoot("mods/b.jar", rootB)), List.of(dependent), Set.of());

		// The dependent's provider is pack root a itself; a needs b, and b is another pack root - it is dragged with the same colliders.
		assertEquals(List.of("a.jar", "b.jar"), paths(candidates));
		assertEquals(List.of(new Collider("mods/one.jar", ROOT_HASH)), candidates.get(0).colliders());
		assertEquals(candidates.get(0).colliders(), candidates.get(1).colliders());
	}

	@Test
	void aNestedProviderDragsAProviderFromADifferentPackRoot() {
		FileInspection.Mod rootA = tree("a.jar", "1.0.0", Set.of("a"), Set.of(),
				tree("/META-INF/jars/p.jar", "2.0.0", Set.of("d"), Set.of("e")));
		FileInspection.Mod rootB = tree("b.jar", "1.0.0", Set.of("b"), Set.of(),
				tree("/META-INF/jars/e.jar", "1.0.0", Set.of("e"), Set.of()));
		StandardRoot dependent = standard("mods/one.jar", Set.of("one"), Set.of("d"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot("mods/a.jar", rootA), packRoot("mods/b.jar", rootB)), List.of(dependent), Set.of());

		// The provider is resolved against the whole pool, so e.jar under pack root b serves p.jar's dependency across roots.
		assertEquals(List.of("nested/a.jar/META-INF/jars/p.jar", "nested/b.jar/META-INF/jars/e.jar"), paths(candidates));
		assertEquals(List.of(new Collider("mods/one.jar", ROOT_HASH)), candidates.get(0).colliders());
		assertEquals(candidates.get(0).colliders(), candidates.get(1).colliders());
	}

	@Test
	void aDependencyProvidedByAStandardRootIsNeverBundled() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/w.jar", "2.0.0", Set.of("a"), Set.of("sib")),
				tree("/META-INF/jars/sib.jar", "1.0.0", Set.of("sib"), Set.of()));
		StandardRoot beaten = standard("mods/local.jar", nested(Set.of("a"), "1.0.0"));
		StandardRoot provider = standard("mods/provider.jar", Set.of("sib"), Set.of());

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(beaten, provider), Set.of());

		// The winner is copied, but its dependency is already served by a standard root - bundling sib.jar would duplicate it.
		assertEquals(List.of("nested/pack.jar/META-INF/jars/w.jar"), paths(candidates));
	}

	@Test
	void aDependencyOfADraggedSiblingIsDraggedToo() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/w.jar", "2.0.0", Set.of("a"), Set.of("d1")),
				tree("/META-INF/jars/d1.jar", "1.0.0", Set.of("d1"), Set.of("d2")),
				tree("/META-INF/jars/d2.jar", "1.0.0", Set.of("d2"), Set.of()));
		StandardRoot standard = standard("mods/local.jar", nested(Set.of("a"), "1.0.0"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(standard), Set.of());

		assertEquals(List.of("nested/pack.jar/META-INF/jars/d1.jar", "nested/pack.jar/META-INF/jars/d2.jar", "nested/pack.jar/META-INF/jars/w.jar"), paths(candidates));
		assertEquals(1, candidates.stream().map(Candidate::colliders).collect(Collectors.toSet()).size());
	}

	@Test
	void unmetStandardDependencyEmitsThePackNestedProviderWithDependentsAsColliders() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/p.jar", "2.0.0", Set.of("d"), Set.of()));
		StandardRoot dependentOne = standard("mods/one.jar", Set.of("one"), Set.of("d"));
		StandardRoot dependentTwo = standard("mods/two.jar", Set.of("two"), Set.of("d"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(dependentOne, dependentTwo), Set.of());

		assertEquals(1, candidates.size());
		assertEquals(Path.of("nested/pack.jar/META-INF/jars/p.jar"), candidates.get(0).mod().path());
		assertEquals(List.of(new Collider("mods/one.jar", ROOT_HASH), new Collider("mods/two.jar", ROOT_HASH)), candidates.get(0).colliders());
	}

	@Test
	void aStandardTopLevelOrNestedProviderSuppressesTheDependencyCopy() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/p.jar", "2.0.0", Set.of("d"), Set.of()));
		StandardRoot dependent = standard("mods/one.jar", Set.of("one"), Set.of("d"));

		StandardRoot topLevelProvider = standard("mods/provider.jar", Set.of("d"), Set.of());
		assertTrue(NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(dependent, topLevelProvider), Set.of()).isEmpty());

		// A standard root nesting a NEWER d satisfies the dependency; an older one would be the classic beaten-collider case instead.
		StandardRoot nestedProvider = standard("mods/provider.jar", Set.of("other"), Set.of(), nested(Set.of("d"), "9.0.0"));
		assertTrue(NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(dependent, nestedProvider), Set.of()).isEmpty());
	}

	@Test
	void aClaimedDependencyDoesNotEmitAnotherCopy() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/w.jar", "2.0.0", Set.of("a"), Set.of("d")),
				tree("/META-INF/jars/d.jar", "1.0.0", Set.of("d"), Set.of()));
		StandardRoot beaten = standard("mods/old.jar", nested(Set.of("a"), "1.0.0"));
		StandardRoot dependent = standard("mods/one.jar", Set.of("one"), Set.of("d"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(beaten, dependent), Set.of());

		// The winner's drag already provides d as a root; the dependent's unmet pass must not emit a second copy.
		assertEquals(List.of("nested/pack.jar/META-INF/jars/d.jar", "nested/pack.jar/META-INF/jars/w.jar"), paths(candidates));
	}

	@Test
	void aDependentOnAnAlreadyEmittedJarJoinsItsColliders() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/w.jar", "2.0.0", Set.of("a"), Set.of("d")),
				tree("/META-INF/jars/d.jar", "1.0.0", Set.of("d"), Set.of()));
		StandardRoot beaten = standard("mods/old.jar", nested(Set.of("a"), "1.0.0"));
		StandardRoot dependent = standard("mods/one.jar", Set.of("one"), Set.of("d"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(beaten, dependent), Set.of());

		// The drag's copy survives while either reason survives: the beaten root that forced the winner, or the dependent.
		Candidate drag = candidates.stream().filter(candidate -> candidate.mod().path().toString().endsWith("d.jar")).findFirst().orElseThrow();
		assertEquals(List.of(new Collider("mods/old.jar", ROOT_HASH), new Collider("mods/one.jar", ROOT_HASH)), drag.colliders());
	}

	@Test
	void twoPackRootsSharingAnIdEmitOnlyOneOfThem() {
		FileInspection.Mod first = tree("r1.jar", "1.0.0", Set.of("lib", "a"), Set.of());
		FileInspection.Mod second = tree("r2.jar", "1.0.0", Set.of("lib", "b"), Set.of());
		StandardRoot one = standard("mods/one.jar", Set.of("one"), Set.of("lib", "b"));
		StandardRoot two = standard("mods/two.jar", Set.of("two"), Set.of("lib"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(first), packRoot(second)), List.of(one, two), Set.of());

		// "b" resolves to the second root first; when "lib" then resolves, the already-emitted second root
		// provides it - and the shared-id guard stops the first root from putting "lib" into the bundle twice.
		assertEquals(1, candidates.size());
		assertEquals(Set.of("lib", "b"), candidates.get(0).mod().IDs());
	}

	@Test
	void aPackRootClaimingTheReservedBundleIdIsRejected() {
		FileInspection.Mod impostor = tree("impostor.jar", "1.0.0", Set.of(GeneratedBundle.MOD_ID), Set.of());

		assertThrows(IllegalArgumentException.class, () -> NestedConflicts.detect(List.of(packRoot(impostor)), List.of(), Set.of()));
	}

	@Test
	void aPreviouslyCopiedJarDoesNotProvisionItsOwnDependency() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/p.jar", "2.0.0", Set.of("d"), Set.of()));
		StandardRoot dependent = standard("mods/one.jar", Set.of("one"), Set.of("d"));
		StandardRoot copy = standard("mods/d-1.0.0.jar", Set.of("d"), Set.of());

		// Without knowing the copy, the scan counts it as provision and satisfies its own dependency - the retirement trap.
		assertEquals(0, NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(dependent, copy), Set.of()).size());
		// Knowing it, the detector keeps emitting the copy while the dependent lives, so the plan never retires it.
		List<Candidate> stable = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(dependent, copy), Set.of(), Set.of("mods/d-1.0.0.jar"), Set.of());
		assertEquals(1, stable.size());
	}

	@Test
	void anUnmetDependencyIsServedByThePackRootItself() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack", "d"), Set.of());
		StandardRoot dependent = standard("mods/one.jar", Set.of("one"), Set.of("d"));

		// A pack-root id no longer suppresses the copy: the pack's authoritative jar is the provider now.
		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(dependent), Set.of("d"), Set.of(), Set.of());

		assertEquals(List.of("pack.jar"), paths(candidates));
		assertEquals(List.of(new Collider("mods/one.jar", ROOT_HASH)), candidates.get(0).colliders());
	}

	@Test
	void aPackRootProviderBeatsANestedProviderOfTheSameId() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack", "d"), Set.of(),
				tree("/META-INF/jars/nested.jar", "9.0.0", Set.of("d"), Set.of()));
		StandardRoot dependent = standard("mods/one.jar", Set.of("one"), Set.of("d"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(dependent), Set.of("d"), Set.of(), Set.of());

		assertEquals(List.of("pack.jar"), paths(candidates));
	}

	@Test
	void aForceCopyPackRootSuppressesTheDependencyCopy() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack", "d"), Set.of());
		StandardRoot dependent = standard("mods/one.jar", Set.of("one"), Set.of("d"));

		// The force-copy lands the real root in the standard mods directory, so the dependency is will-be-provided.
		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot("mods/pack.jar", packRoot)), List.of(dependent), Set.of("d"), Set.of(), Set.of("MODS/Pack.jar"));

		assertTrue(candidates.isEmpty());
	}

	@Test
	void aForceCopyPackRootAlsoProvidesItsNestedIds() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/d.jar", "1.0.0", Set.of("d"), Set.of()));
		StandardRoot dependent = standard("mods/one.jar", Set.of("one"), Set.of("d"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot("mods/pack.jar", packRoot)), List.of(dependent), Set.of("pack"), Set.of(), Set.of("mods/pack.jar"));

		assertTrue(candidates.isEmpty());
	}

	@Test
	void aPreviouslyGeneratedBundleDoesNotProvisionItsContentsDependencies() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/p.jar", "2.0.0", Set.of("d"), Set.of()));
		StandardRoot dependent = standard("mods/one.jar", Set.of("one"), Set.of("d"));
		String bundlePath = "mods/" + ModpackPathPolicy.GENERATED_BUNDLE_NAME;
		StandardRoot bundle = standard(bundlePath, Set.of("automodpack_generated", "d"), Set.of());

		// Without knowing the bundle, its ids count as provision and the emission would retire itself.
		assertEquals(0, NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(dependent, bundle), Set.of("pack"), Set.of(), Set.of()).size());
		// Knowing the reserved path, the detector keeps emitting while the dependent lives.
		List<Candidate> stable = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(dependent, bundle), Set.of("pack"), Set.of(bundlePath), Set.of());
		assertEquals(1, stable.size());
		assertEquals(Path.of("nested/pack.jar/META-INF/jars/p.jar"), stable.get(0).mod().path());
		assertEquals(List.of(new Collider("mods/one.jar", ROOT_HASH)), stable.get(0).colliders());
	}

	@Test
	void aDependencyDrivenProviderDragsItsOwnDependencies() {
		FileInspection.Mod packRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/p.jar", "2.0.0", Set.of("d"), Set.of("d2")),
				tree("/META-INF/jars/d2.jar", "1.0.0", Set.of("d2"), Set.of()));
		StandardRoot dependent = standard("mods/one.jar", Set.of("one"), Set.of("d"));

		List<Candidate> candidates = NestedConflicts.detect(List.of(packRoot(packRoot)), List.of(dependent), Set.of());

		assertEquals(List.of("nested/pack.jar/META-INF/jars/d2.jar", "nested/pack.jar/META-INF/jars/p.jar"), paths(candidates));
		assertEquals(1, candidates.stream().map(Candidate::colliders).collect(Collectors.toSet()).size());
	}

	private static List<String> paths(List<Candidate> candidates) {
		return candidates.stream().map(candidate -> candidate.mod().path().toString().replace('\\', '/')).toList();
	}

	private static NestedConflicts.PackRoot packRoot(FileInspection.Mod tree) {
		return packRoot("mods/pack.jar", tree);
	}

	private static NestedConflicts.PackRoot packRoot(String logicalPath, FileInspection.Mod tree) {
		return new NestedConflicts.PackRoot(logicalPath, tree, Path.of("nested").resolve(tree.path()));
	}

	private static FileInspection.Mod nested(Set<String> ids, String version) {
		return new FileInspection.Mod(ids, null, version, Path.of("/META-INF/jars/nested.jar"), Set.of(), Set.of());
	}

	private static FileInspection.Mod tree(String path, String version, Set<String> ids, Set<String> deps, FileInspection.Mod... nested) {
		return new FileInspection.Mod(ids, null, version, Path.of(path), deps, Set.of(nested));
	}

	private static StandardRoot standard(String logicalPath, FileInspection.Mod... nested) {
		return standard(logicalPath, Set.of("root"), Set.of(), nested);
	}

	private static StandardRoot standard(String logicalPath, Set<String> ids, Set<String> deps, FileInspection.Mod... nested) {
		return new StandardRoot(logicalPath, new FileInspection.Mod(ids, ROOT_HASH, "1.0.0", Path.of(logicalPath), deps, Set.of(nested)));
	}
}
