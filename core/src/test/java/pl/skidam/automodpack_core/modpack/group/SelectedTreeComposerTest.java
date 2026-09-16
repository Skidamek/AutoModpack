package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;

class SelectedTreeComposerTest {
	@Test
	void deduplicatesSharedFileAndKeepsItWhileOneOwnerRemains() {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(1, "mod", false,
				"86f7e437faa5a7fce15d1ddcb9eaeaea377667b8", null);
		GroupManifest manifest = manifest(Map.of("main", group(file), "visuals", group(file)));

		var both = SelectedTreeComposer.compose(manifest,
				new ResolvedSelection(new SelectionIntent(Set.of("main", "visuals")), new TreeSet<>(Set.of("main", "visuals")), new TreeSet<>()));
		var one = SelectedTreeComposer.compose(manifest,
				new ResolvedSelection(new SelectionIntent(Set.of("visuals")), new TreeSet<>(Set.of("visuals")), new TreeSet<>()));

		assertEquals(1, both.list.size());
		assertEquals(1, one.list.size());
		assertEquals(Set.of("main", "visuals"), both.selectedGroups);
	}

	@Test
	void selectionChangeKeepsTargetIdentity() {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(1, "mod", false,
				"86f7e437faa5a7fce15d1ddcb9eaeaea377667b8", null);
		GroupManifest manifest = manifest(Map.of("main", group(file), "visuals", group(file)));
		PackDocument document = TestPacks.document(manifest);

		SelectedModpackTarget main = SelectedModpackTarget.prepare(TestPacks.head(manifest), null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
		SelectedModpackTarget visuals = SelectedModpackTarget.prepare(TestPacks.head(manifest), null, new SelectionIntent(Set.of("visuals")), ClientPlatform.LINUX);

		assertEquals(document.contentToken(), main.flatTarget().contentToken);
		assertEquals(main.flatTarget().contentToken, visuals.flatTarget().contentToken);
		assertEquals(document.policySha1(), main.flatTarget().policySha1);
		assertNotEquals(main.flatTarget().selectedGroups, visuals.flatTarget().selectedGroups);
	}

	@Test
	void selectsCorrectMutuallyExclusiveVariant() {
		GroupManifest.GroupFile first = new GroupManifest.GroupFile(1, "mod", false,
				"86f7e437faa5a7fce15d1ddcb9eaeaea377667b8", null);
		GroupManifest.GroupFile second = new GroupManifest.GroupFile(1, "mod", false,
				"e9d71f5ee7c92d6dc9e92ffdad17b8bd49418f98", null);
		GroupManifest manifest = manifest(Map.of("first", group(first), "second", group(second)));

		var target = SelectedTreeComposer.compose(manifest,
				new ResolvedSelection(new SelectionIntent(Set.of("second")), new TreeSet<>(Set.of("second")), new TreeSet<>()));

		assertEquals(second.sha1(), target.list.iterator().next().sha1);
	}

	@Test
	void composesCompleteCatalogueForPreloadWithoutChangingSelection() {
		GroupManifest.GroupFile first = new GroupManifest.GroupFile(1, "config", false,
				"86f7e437faa5a7fce15d1ddcb9eaeaea377667b8", null);
		GroupManifest.GroupFile second = new GroupManifest.GroupFile(1, "config", false,
				"e9d71f5ee7c92d6dc9e92ffdad17b8bd49418f98", null);
		GroupManifest manifest = manifest(Map.of("main", new GroupManifest.Group("", "", "", true, true, new TreeSet<>(), new TreeSet<>(), Set.of(), new TreeMap<>(Map.of("config/first.txt", first))), "optional",
				new GroupManifest.Group("", "", "", false, false, new TreeSet<>(), new TreeSet<>(), Set.of(),
						new TreeMap<>(Map.of("config/second.txt", second)))));
		SelectedModpackTarget target = SelectedModpackTarget.prepareDefault(TestPacks.head(manifest), ClientPlatform.LINUX);

		assertEquals(2, target.completeTarget().list.size());
		assertNotEquals(Set.of("main", "optional"), target.flatTarget().selectedGroups);
	}

	@Test
	void composesAlternativeCatalogueObjectsWithTheSamePath() {
		GroupManifest.GroupFile first = new GroupManifest.GroupFile(1, "config", false, "base-hash", null);
		GroupManifest.GroupFile second = new GroupManifest.GroupFile(1, "config", false, "alternative-hash", null);
		GroupManifest manifest = manifest(Map.of("base", group(first), "alternative", group(second)));

		var target = SelectedTreeComposer.composeAll(manifest, null);

		assertEquals(Set.of("base-hash", "alternative-hash"), target.list.stream().map(item -> item.sha1).collect(Collectors.toSet()));
	}

	private static GroupManifest manifest(Map<String, GroupManifest.Group> groups) {
		return new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(groups));
	}

	private static GroupManifest.Group group(GroupManifest.GroupFile file) {
		return new GroupManifest.Group("", "", "", false, false, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("mods/example.jar", file)));
	}
}
