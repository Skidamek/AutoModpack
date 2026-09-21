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
	void unparseableVersionsCompeteByExactStringEqualityOnly() {
		StandardRoot unparseable = standard("mods/local.jar", nested(Set.of("a"), "nightly"));
		StandardRoot parseable = standard("mods/local.jar", nested(Set.of("a"), "1.0.0"));
		FileInspection.Mod parseablePackRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/a.jar", "2.0.0", Set.of("a"), Set.of()));
		FileInspection.Mod unparseablePackRoot = tree("pack.jar", "1.0.0", Set.of("pack"), Set.of(),
				tree("/META-INF/jars/a.jar", "nightly", Set.of("a"), Set.of()));

		// The pack side can never be proven newer than an unparseable version, and an unparseable pack version can never be proven newer.
		assertTrue(NestedConflicts.detect(List.of(packRoot(parseablePackRoot)), List.of(unparseable), Set.of()).isEmpty());
		assertTrue(NestedConflicts.detect(List.of(packRoot(unparseablePackRoot)), List.of(parseable), Set.of()).isEmpty());
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

	private static List<String> paths(List<Candidate> candidates) {
		return candidates.stream().map(candidate -> candidate.mod().path().toString()).toList();
	}

	private static NestedConflicts.PackRoot packRoot(FileInspection.Mod tree) {
		return new NestedConflicts.PackRoot(tree, Path.of("nested").resolve(tree.path()));
	}

	private static FileInspection.Mod nested(Set<String> ids, String version) {
		return new FileInspection.Mod(ids, null, version, Path.of("/META-INF/jars/nested.jar"), Set.of(), Set.of());
	}

	private static FileInspection.Mod tree(String path, String version, Set<String> ids, Set<String> deps, FileInspection.Mod... nested) {
		return new FileInspection.Mod(ids, null, version, Path.of(path), deps, Set.of(nested));
	}

	private static StandardRoot standard(String logicalPath, FileInspection.Mod... nested) {
		return new StandardRoot(logicalPath, new FileInspection.Mod(Set.of("root"), ROOT_HASH, "1.0.0", Path.of(logicalPath), Set.of(), Set.of(nested)));
	}
}
