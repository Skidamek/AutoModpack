package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

class GroupSelectionResolverTest {
	@Test
	void defaultSelectedGroupsEnterOnlyTheNewDefaultIntent() {
		GroupManifest manifest = manifest(Map.of("default", group(false, true, Set.of()), "optional", group(false, false, Set.of())));

		SelectionIntent defaults = GroupSelectionResolver.defaultIntent(manifest);

		assertEquals(Set.of("default"), defaults.requestedGroups());
		assertEquals(Set.of(), GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of()), ClientPlatform.LINUX).selectedGroups());
		assertEquals(Set.of("default"), GroupSelectionResolver.resolveDefault(manifest, ClientPlatform.LINUX).selectedGroups());
	}

	@Test
	void requiredGroupsRemainDistinctFromDefaultSelectedIntent() {
		GroupManifest manifest = manifest(Map.of("required", group(true, true, Set.of()), "default", group(false, true, Set.of())));

		SelectionIntent defaults = GroupSelectionResolver.defaultIntent(manifest);

		assertEquals(Set.of("default"), defaults.requestedGroups());
		assertEquals(Set.of("default", "required"), GroupSelectionResolver.resolveDefault(manifest, ClientPlatform.LINUX).selectedGroups());
	}

	@Test
	void explicitlyRequestedUnavailableOptionalGroupInvalidatesResolution() {
		GroupManifest manifest = manifest(Map.of("windows", new GroupManifest.Group("Windows", "", "", "", false, false, new TreeSet<>(), new TreeSet<>(),
				Set.of(ClientPlatform.WINDOWS), new TreeMap<>())));

		SelectionResolutionException failure = assertThrows(SelectionResolutionException.class,
				() -> GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of("windows")), ClientPlatform.LINUX));

		assertEquals(Set.of("windows"), failure.resolution().requestedUnavailableGroups());
		assertEquals(Set.of("windows"), failure.resolution().unavailableGroups());
		assertTrue(failure.errors().get(0).contains("explicitly requested"));
	}

	@Test
	void explicitlyRequestedOptionalGroupBlockedByUnavailableDependencyIsHonest() {
		GroupManifest.Group dependency = new GroupManifest.Group("Dependency", "", "", "", false, false, new TreeSet<>(), new TreeSet<>(), Set.of(ClientPlatform.WINDOWS), new TreeMap<>());
		GroupManifest.Group feature = new GroupManifest.Group("Feature", "", "", "", false, false, new TreeSet<>(), new TreeSet<>(Set.of("dependency")), Set.of(), new TreeMap<>());
		GroupManifest manifest = manifest(Map.of("dependency", dependency, "feature", feature));

		SelectionResolutionException failure = assertThrows(SelectionResolutionException.class,
				() -> GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of("feature")), ClientPlatform.LINUX));

		assertEquals(Set.of("feature"), failure.resolution().requestedUnavailableGroups());
		assertEquals(GroupResolution.Status.BLOCKED, failure.resolution().explanation("feature").status());
		assertEquals(Set.of("dependency"), failure.resolution().explanation("feature").relatedGroups());
	}

	@Test
	void unsupportedGroupsNeverRequestedRemainOutsideRequestedUnavailableSubset() {
		GroupManifest manifest = manifest(Map.of("windows", new GroupManifest.Group("Windows", "", "", "", false, false, new TreeSet<>(), new TreeSet<>(),
				Set.of(ClientPlatform.WINDOWS), new TreeMap<>())));

		ResolvedSelection resolved = GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of()), ClientPlatform.LINUX);

		assertEquals(Set.of("windows"), resolved.unavailableGroups());
		assertTrue(resolved.requestedUnavailableGroups().isEmpty());
	}

	@Test
	void dependencyExplanationNamesTheSelectedGroupThatRequiresIt() {
		GroupManifest manifest = manifest(Map.of("dependency", group(false, false, Set.of()), "feature", group(false, false, Set.of("dependency"))));

		ResolvedSelection resolved = GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of("feature")), ClientPlatform.LINUX);

		assertEquals(Set.of("feature"), resolved.explanation("dependency").relatedGroups());
	}

	@Test
	void categoryTogglePersistsCategoryWithoutPretendingItsGroupsWereClicked() {
		GroupManifest manifest = manifest(Map.of("one", categorized("one", ClientPlatform.LINUX), "two", categorized("two", ClientPlatform.LINUX)));

		SelectionIntent selected = GroupSelectionResolver.preferCategory(manifest, new SelectionIntent(Set.of()), "visuals", ClientPlatform.LINUX);

		assertEquals(Set.of(), selected.requestedGroups());
		assertEquals(Set.of("visuals"), selected.requestedCategories());
		assertEquals(Set.of("one", "two"), GroupSelectionResolver.resolve(manifest, selected, ClientPlatform.LINUX).selectedGroups());
		assertEquals(Set.of(), selected.excludedGroups());
	}

	@Test
	void individualGroupDoesNotSelectItsSingleGroupCategory() {
		GroupManifest manifest = manifest(Map.of("one", categorized("one", ClientPlatform.LINUX)));

		SelectionIntent selected = GroupSelectionResolver.prefer(manifest, new SelectionIntent(Set.of()), "one", ClientPlatform.LINUX);

		assertEquals(Set.of("one"), selected.requestedGroups());
		assertTrue(selected.requestedCategories().isEmpty());
	}

	@Test
	void changingAChildConvertsTheRestOfItsSelectedCategoryToIndividualChoices() {
		GroupManifest manifest = manifest(Map.of("one", categorized("one", ClientPlatform.LINUX), "two", categorized("two", ClientPlatform.LINUX)));
		SelectionIntent category = GroupSelectionResolver.preferCategory(manifest, new SelectionIntent(Set.of()), "visuals", ClientPlatform.LINUX);

		SelectionIntent changed = GroupSelectionResolver.prefer(manifest, category, "one", ClientPlatform.LINUX);

		assertEquals(Set.of("two"), changed.requestedGroups());
		assertTrue(changed.requestedCategories().isEmpty());
	}

	private static GroupManifest manifest(Map<String, GroupManifest.Group> groups) {
		return new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(groups));
	}

	private static GroupManifest.Group group(boolean required, boolean defaultSelected, Set<String> requires) {
		return new GroupManifest.Group("", "", "", "", required, defaultSelected, new TreeSet<>(), new TreeSet<>(requires), Set.of(), new TreeMap<>());
	}

	private static GroupManifest.Group categorized(String name, ClientPlatform platform) {
		return new GroupManifest.Group(name, "", "visuals", "", false, false, new TreeSet<>(), new TreeSet<>(), Set.of(platform), new TreeMap<>());
	}
}
