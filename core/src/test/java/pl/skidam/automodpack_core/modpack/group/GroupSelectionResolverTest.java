package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

class GroupSelectionResolverTest {
	@Test
	void keepsExplicitIntentSeparateFromRequiredDependencyClosure() {
		GroupManifest manifest = manifest(Map.of(
				"api", group(false, false, Set.of()),
				"core", group(true, false, Set.of()),
				"feature", group(false, false, Set.of("api"))));
		SelectionIntent intent = new SelectionIntent(Set.of("feature"));

		ResolvedSelection resolved = GroupSelectionResolver.resolve(manifest, intent, ClientPlatform.LINUX);

		assertEquals(Set.of("feature"), resolved.intent().requestedGroups());
		assertEquals(Set.of("api", "core", "feature"), resolved.selectedGroups());
	}

	@Test
	void rejectsConflictingExplicitIntentInsteadOfChoosingByOrder() {
		var first = group(false, false, Set.of());
		var second = new GroupManifest.Group("", "", "", false, false, new TreeSet<>(Set.of("first")), new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>());
		GroupManifest manifest = manifest(Map.of("first", first, "second", second));

		assertThrows(SelectionResolutionException.class,
				() -> GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of("first", "second")), ClientPlatform.LINUX));
	}

	@Test
	void clickedGroupWinsWhenConflictComesThroughDependencyClosure() {
		var dependency = new GroupManifest.Group("", "", "", false, false, new TreeSet<>(Set.of("conflicting")), new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>());
		var clicked = group(false, false, Set.of("dependency"));
		var conflicting = group(false, false, Set.of());
		GroupManifest manifest = manifest(Map.of("clicked", clicked, "dependency", dependency, "conflicting", conflicting));

		SelectionIntent preferred = GroupSelectionResolver.prefer(manifest, new SelectionIntent(Set.of("conflicting")), "clicked", ClientPlatform.LINUX);

		assertEquals(Set.of("clicked"), preferred.requestedGroups());
		assertEquals(Set.of("clicked", "dependency"), GroupSelectionResolver.resolve(manifest, preferred, ClientPlatform.LINUX).selectedGroups());
	}

	@Test
	void defaultsUseRecommendedAndDefaultTagsOnlyForNewIntent() {
		var tagged = new GroupManifest.Group("", "", "", false, false, new TreeSet<>(), new TreeSet<>(), new TreeSet<>(Set.of("recommended")), Set.of(),
				new TreeMap<>());
		GroupManifest manifest = new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(Map.of("tagged", tagged)),
				new TreeMap<>(Map.of("recommended", new GroupManifest.SelectionTag("", "", true, false))));

		assertEquals(Set.of("tagged"), GroupSelectionResolver.defaultIntent(manifest).requestedGroups());
		assertEquals(Set.of(), GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of()), ClientPlatform.LINUX).intent().requestedGroups());
	}

	@Test
	void filtersUnavailableOptionalGroupsWithoutDiscardingIntent() {
		var windowsOnly = new GroupManifest.Group("", "", "", false, true, new TreeSet<>(), new TreeSet<>(), new TreeSet<>(), Set.of(ClientPlatform.WINDOWS),
				new TreeMap<>());
		GroupManifest manifest = manifest(Map.of("windows", windowsOnly));
		SelectionIntent intent = GroupSelectionResolver.defaultIntent(manifest);

		ResolvedSelection resolved = GroupSelectionResolver.resolve(manifest, intent, ClientPlatform.LINUX);

		assertEquals(Set.of("windows"), resolved.intent().requestedGroups());
		assertTrue(resolved.selectedGroups().isEmpty());
	}

	@Test
	void reportsRemovedRequestedGroupsAsStale() {
		GroupManifest manifest = manifest(Map.of("main", group(false, false, Set.of())));

		ResolvedSelection resolved = GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of("removed")), ClientPlatform.LINUX);

		assertEquals(Set.of("removed"), resolved.staleRequestedGroups());
	}

	private static GroupManifest manifest(Map<String, GroupManifest.Group> groups) {
		return new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(groups), new TreeMap<>());
	}

	private static GroupManifest.Group group(boolean required, boolean recommended, Set<String> requires) {
		return new GroupManifest.Group("", "", "", required, recommended, new TreeSet<>(), new TreeSet<>(requires), new TreeSet<>(), Set.of(), new TreeMap<>());
	}
}
