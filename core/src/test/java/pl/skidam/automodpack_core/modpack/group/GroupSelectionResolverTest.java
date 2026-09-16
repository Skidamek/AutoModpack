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
		var second = new GroupManifest.Group("", "", "", false, false, new TreeSet<>(Set.of("first")), new TreeSet<>(), Set.of(),
				new TreeMap<>());
		GroupManifest manifest = manifest(Map.of("first", first, "second", second));

		assertThrows(SelectionResolutionException.class,
				() -> GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of("first", "second")), ClientPlatform.LINUX));
	}

	@Test
	void clickedGroupKeepsConflictingIntentForExplicitResolution() {
		var dependency = new GroupManifest.Group("", "", "", false, false, new TreeSet<>(Set.of("conflicting")), new TreeSet<>(), Set.of(),
				new TreeMap<>());
		var clicked = group(false, false, Set.of("dependency"));
		var conflicting = group(false, false, Set.of());
		GroupManifest manifest = manifest(Map.of("clicked", clicked, "dependency", dependency, "conflicting", conflicting));

		SelectionIntent preferred = GroupSelectionResolver.prefer(new SelectionIntent(Set.of("conflicting")), "clicked");

		assertEquals(Set.of("clicked", "conflicting"), preferred.requestedGroups());
		SelectionResolutionException failure = assertThrows(SelectionResolutionException.class,
				() -> GroupSelectionResolver.resolve(manifest, preferred, ClientPlatform.LINUX));
		assertEquals(GroupResolution.Status.CONFLICT, failure.resolution().explanation("dependency").status());
	}

	@Test
	void defaultsUseRecommendedAndDefaultTagsOnlyForNewIntent() {
		var tagged = new GroupManifest.Group("", "", "recommended", false, false, new TreeSet<>(), new TreeSet<>(), Set.of(), new TreeMap<>());
		GroupManifest manifest = new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(Map.of("tagged", tagged)),
				new TreeMap<>(Map.of("recommended", new GroupManifest.SelectionTag("", "", true, false))));

		SelectionIntent defaults = GroupSelectionResolver.defaultIntent(manifest);
		assertEquals(Set.of("recommended"), defaults.requestedTags());
		assertEquals(Set.of(), defaults.requestedGroups());
		assertEquals(Set.of(), GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of()), ClientPlatform.LINUX).intent().requestedGroups());
	}

	@Test
	void defaultIntentKeepsRequiredAndForcedContentDerived() {
		GroupManifest.Group required = group(true, true, Set.of());
		GroupManifest.Group forced = new GroupManifest.Group("", "", "forced", false, true, new TreeSet<>(), new TreeSet<>(), Set.of(), new TreeMap<>());
		GroupManifest.Group recommended = group(false, true, Set.of());
		GroupManifest manifest = new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(Map.of("required", required, "forced", forced, "recommended", recommended)),
				new TreeMap<>(Map.of("forced", new GroupManifest.SelectionTag("", "", false, true))));

		SelectionIntent defaults = GroupSelectionResolver.defaultIntent(manifest);

		assertEquals(Set.of(), defaults.requestedTags());
		assertEquals(Set.of("recommended"), defaults.requestedGroups());
		assertEquals(Set.of("required", "forced", "recommended"), GroupSelectionResolver.resolve(manifest, defaults, ClientPlatform.LINUX).selectedGroups());
	}

	@Test
	void filtersUnavailableOptionalGroupsWithoutDiscardingIntent() {
		var windowsOnly = new GroupManifest.Group("", "", "", false, true, new TreeSet<>(), new TreeSet<>(), Set.of(ClientPlatform.WINDOWS),
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

	@Test
	void selectingATagExpandsEveryCompatibleGroupAndTransitiveDependencies() {
		GroupManifest.Group core = group(false, false, Set.of());
		GroupManifest.Group api = new GroupManifest.Group("", "", "", false, false, new TreeSet<>(), new TreeSet<>(Set.of("core")), Set.of(), new TreeMap<>());
		GroupManifest.Group feature = new GroupManifest.Group("", "", "visuals", false, false, new TreeSet<>(), new TreeSet<>(Set.of("api")), Set.of(), new TreeMap<>());
		GroupManifest.Group ui = new GroupManifest.Group("", "", "visuals", false, false, new TreeSet<>(), new TreeSet<>(), Set.of(), new TreeMap<>());
		GroupManifest manifest = new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(Map.of("core", core, "api", api, "feature", feature, "ui", ui)),
				new TreeMap<>(Map.of("visuals", new GroupManifest.SelectionTag("", "", false, false))));

		ResolvedSelection resolved = GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of("visuals"), Set.of()), ClientPlatform.LINUX);

		assertEquals(Set.of("api", "core", "feature", "ui"), resolved.selectedGroups());
		assertEquals(Set.of("feature", "ui"), resolved.tagSelectedGroups());
		assertEquals(Set.of("api", "core"), resolved.dependencyGroups());
		assertEquals(GroupResolution.Status.SELECTED, resolved.explanation("api").status());
		assertEquals(GroupResolution.Reason.DEPENDENCY, resolved.explanation("api").reasons().stream().filter(reason -> reason == GroupResolution.Reason.DEPENDENCY).findFirst().orElseThrow());
	}

	@Test
	void selectsOnlyCompatibleGroupsFromOneTagBundle() {
		GroupManifest.Group windows = new GroupManifest.Group("", "", "bundle", false, false, new TreeSet<>(), new TreeSet<>(), Set.of(ClientPlatform.WINDOWS), new TreeMap<>());
		GroupManifest.Group linux = new GroupManifest.Group("", "", "bundle", false, false, new TreeSet<>(), new TreeSet<>(), Set.of(ClientPlatform.LINUX), new TreeMap<>());
		GroupManifest manifest = new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(Map.of("windows", windows, "linux", linux)),
				new TreeMap<>(Map.of("bundle", new GroupManifest.SelectionTag("", "", false, false))));

		ResolvedSelection resolved = GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of("bundle"), Set.of()), ClientPlatform.LINUX);

		assertEquals(Set.of("linux"), resolved.selectedGroups());
		assertEquals(GroupResolution.Status.UNAVAILABLE, resolved.explanation("windows").status());
		assertEquals(Set.of("linux"), resolved.tagSelectedGroups());
	}

	@Test
	void preservesStaleTagsAndGroupsAndExplainsUnsupportedOptionalGroups() {
		GroupManifest.Group windowsOnly = new GroupManifest.Group("", "", "", false, false, new TreeSet<>(), new TreeSet<>(), Set.of(ClientPlatform.WINDOWS), new TreeMap<>());
		GroupManifest manifest = manifest(Map.of("windows", windowsOnly));
		SelectionIntent intent = new SelectionIntent(Set.of("removed-tag"), Set.of("windows", "removed-group"));

		ResolvedSelection resolved = GroupSelectionResolver.resolve(manifest, intent, ClientPlatform.LINUX);

		assertEquals(Set.of("removed-tag"), resolved.staleRequestedTags());
		assertEquals(Set.of("removed-group"), resolved.staleRequestedGroups());
		assertTrue(resolved.selectedGroups().isEmpty());
		assertEquals(GroupResolution.Status.UNAVAILABLE, resolved.explanation("windows").status());
		assertTrue(resolved.explanation("windows").reasons().contains(GroupResolution.Reason.PLATFORM_INCOMPATIBLE));
	}

	@Test
	void requiredAndForcedUnsupportedGroupsInvalidateResolution() {
		GroupManifest.Group required = new GroupManifest.Group("", "", "", true, false, new TreeSet<>(), new TreeSet<>(), Set.of(ClientPlatform.WINDOWS), new TreeMap<>());
		GroupManifest requiredManifest = manifest(Map.of("required", required));
		SelectionResolutionException requiredFailure = assertThrows(SelectionResolutionException.class,
				() -> GroupSelectionResolver.resolve(requiredManifest, new SelectionIntent(Set.of()), ClientPlatform.LINUX));
		assertTrue(requiredFailure.errors().stream().anyMatch(error -> error.contains("required") && error.contains("unavailable")));
		assertEquals(GroupResolution.Status.UNAVAILABLE, requiredFailure.resolution().explanation("required").status());

		GroupManifest.Group forced = new GroupManifest.Group("", "", "forced", false, false, new TreeSet<>(), new TreeSet<>(), Set.of(ClientPlatform.WINDOWS), new TreeMap<>());
		GroupManifest forcedManifest = new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(Map.of("forced", forced)),
				new TreeMap<>(Map.of("forced", new GroupManifest.SelectionTag("", "", false, true))));
		assertThrows(SelectionResolutionException.class, () -> GroupSelectionResolver.resolve(forcedManifest, new SelectionIntent(Set.of()), ClientPlatform.LINUX));
	}

	@Test
	void reportsCrossTagConflictWithStructuredExplanations() {
		GroupManifest.Group tagged = new GroupManifest.Group("", "", "visuals", false, false, new TreeSet<>(), new TreeSet<>(), Set.of(), new TreeMap<>());
		GroupManifest.Group explicit = new GroupManifest.Group("", "", "", false, false, new TreeSet<>(Set.of("tagged")), new TreeSet<>(), Set.of(), new TreeMap<>());
		GroupManifest manifest = new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(Map.of("tagged", tagged, "explicit", explicit)),
				new TreeMap<>(Map.of("visuals", new GroupManifest.SelectionTag("", "", false, false))));

		SelectionResolutionException failure = assertThrows(SelectionResolutionException.class,
				() -> GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of("visuals"), Set.of("explicit")), ClientPlatform.LINUX));

		assertEquals(GroupResolution.Status.CONFLICT, failure.resolution().explanation("tagged").status());
		assertEquals(Set.of("explicit"), failure.resolution().explanation("tagged").relatedGroups());
	}

	@Test
	void excludedDependencyProducesStructuredBlockedExplanation() {
		GroupManifest.Group dependency = group(false, false, Set.of());
		GroupManifest.Group feature = new GroupManifest.Group("", "", "", false, false, new TreeSet<>(), new TreeSet<>(Set.of("dependency")), Set.of(), new TreeMap<>());
		GroupManifest manifest = manifest(Map.of("dependency", dependency, "feature", feature));

		SelectionResolutionException failure = assertThrows(SelectionResolutionException.class,
				() -> GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of(), Set.of("feature"), Set.of("dependency")), ClientPlatform.LINUX));

		assertEquals(GroupResolution.Status.BLOCKED, failure.resolution().explanation("feature").status());
		assertTrue(failure.resolution().explanation("feature").reasons().contains(GroupResolution.Reason.BLOCKED_BY_DEPENDENCY));
	}

	private static GroupManifest manifest(Map<String, GroupManifest.Group> groups) {
		return new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(groups), new TreeMap<>());
	}

	private static GroupManifest.Group group(boolean required, boolean recommended, Set<String> requires) {
		return new GroupManifest.Group("", "", "", required, recommended, new TreeSet<>(), new TreeSet<>(requires), Set.of(), new TreeMap<>());
	}
}
