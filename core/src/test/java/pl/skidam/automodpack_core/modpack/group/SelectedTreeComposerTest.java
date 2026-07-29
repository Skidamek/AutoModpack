package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;

class SelectedTreeComposerTest {
	@Test
	void deduplicatesSharedFileAndKeepsItWhileOneOwnerRemains() {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(1, "mod", false, false, false,
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
	void selectionChangeKeepsGenerationIdentity() {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(1, "mod", false, false, false,
				"86f7e437faa5a7fce15d1ddcb9eaeaea377667b8", null);
		GroupManifest manifest = manifest(Map.of("main", group(file), "visuals", group(file)));
		GenerationRecord record = GenerationRecord.create(manifest, "", Instant.parse("2026-01-01T00:00:00Z"), "");

		SelectedModpackTarget main = SelectedModpackTarget.prepare(record.toFields(), null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
		SelectedModpackTarget visuals = SelectedModpackTarget.prepare(record.toFields(), null, new SelectionIntent(Set.of("visuals")), ClientPlatform.LINUX);

		assertEquals(record.metadata().generationId(), main.flatTarget().targetGenerationId);
		assertEquals(main.flatTarget().targetGenerationId, visuals.flatTarget().targetGenerationId);
		assertNotEquals(main.flatTarget().selectedGroups, visuals.flatTarget().selectedGroups);
	}

	@Test
	void selectsCorrectMutuallyExclusiveVariant() {
		GroupManifest.GroupFile first = new GroupManifest.GroupFile(1, "mod", false, false, false,
				"86f7e437faa5a7fce15d1ddcb9eaeaea377667b8", null);
		GroupManifest.GroupFile second = new GroupManifest.GroupFile(1, "mod", false, false, false,
				"e9d71f5ee7c92d6dc9e92ffdad17b8bd49418f98", null);
		GroupManifest manifest = manifest(Map.of("first", group(first), "second", group(second)));

		var target = SelectedTreeComposer.compose(manifest,
				new ResolvedSelection(new SelectionIntent(Set.of("second")), new TreeSet<>(Set.of("second")), new TreeSet<>()));

		assertEquals(second.sha1(), target.list.iterator().next().sha1);
	}

	private static GroupManifest manifest(Map<String, GroupManifest.Group> groups) {
		return new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(groups), new TreeMap<>(), List.of());
	}

	private static GroupManifest.Group group(GroupManifest.GroupFile file) {
		return new GroupManifest.Group("", "", "", false, false, new TreeSet<>(), new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("mods/example.jar", file)));
	}
}
