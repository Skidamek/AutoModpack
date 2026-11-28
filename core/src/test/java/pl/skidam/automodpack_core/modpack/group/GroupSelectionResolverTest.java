package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

class GroupSelectionResolverTest {
	@Test
	void recommendedArrivesSelectedUnderDefaultsAndRespectsExplicitOptOut() {
		GroupManifest manifest = manifest(Map.of(),
				Map.of("main", group(GroupSelectionMode.REQUIRED, Set.of()), "default", group(GroupSelectionMode.RECOMMENDED, Set.of()),
						"optional", group(GroupSelectionMode.OPTIONAL, Set.of())));

		ResolvedSelection defaults = GroupSelectionResolver.resolveDefault(manifest, ClientPlatform.LINUX);
		assertEquals(Set.of("main", "default"), defaults.selectedGroups());
		assertEquals(Set.of("main", "default"), GroupSelectionResolver.resolve(manifest, SelectionIntent.DEFAULTS, ClientPlatform.LINUX).selectedGroups());

		GroupSelectionResolver.SelectionUpdate optedOut = GroupSelectionResolver.toggleGroup(manifest, SelectionIntent.DEFAULTS, "default", ClientPlatform.LINUX);
		assertEquals("", optedOut.error());
		assertEquals(Set.of("default"), optedOut.intent().disabled());
		assertEquals(Set.of("main"), optedOut.resolution().selectedGroups());

		// A new RECOMMENDED group added later still arrives selected automatically without wiping prior opt-out.
		GroupManifest evolved = manifest(Map.of(),
				Map.of("main", group(GroupSelectionMode.REQUIRED, Set.of()), "default", group(GroupSelectionMode.RECOMMENDED, Set.of()),
						"new-rec", group(GroupSelectionMode.RECOMMENDED, Set.of())));
		ResolvedSelection resolvedEvolved = GroupSelectionResolver.resolve(evolved, optedOut.intent(), ClientPlatform.LINUX);
		assertEquals(Set.of("main", "new-rec"), resolvedEvolved.selectedGroups());
	}

	@Test
	void explicitlyRequestedUnavailableOptionalGroupInvalidatesResolution() {
		GroupManifest manifest = manifest(Map.of(),
				Map.of("windows", new GroupManifest.Group("Windows", "", "", GroupSelectionMode.OPTIONAL, new TreeSet<>(), Set.of(ClientPlatform.WINDOWS), new TreeMap<>())));

		SelectionResolutionException failure = assertThrows(SelectionResolutionException.class,
				() -> GroupSelectionResolver.resolve(manifest, SelectionIntent.ofEnabled(Set.of("windows")), ClientPlatform.LINUX));

		assertEquals(Set.of("windows"), failure.resolution().requestedUnavailableGroups());
		assertEquals(Set.of("windows"), failure.resolution().unavailableGroups());
		assertTrue(failure.errors().get(0).contains("windows"));
	}

	@Test
	void explicitlyRequestedOptionalGroupBlockedByUnavailableDependencyIsHonest() {
		GroupManifest.Group dependency = new GroupManifest.Group("Dependency", "", "", GroupSelectionMode.OPTIONAL, new TreeSet<>(), Set.of(ClientPlatform.WINDOWS), new TreeMap<>());
		GroupManifest.Group feature = new GroupManifest.Group("Feature", "", "", GroupSelectionMode.OPTIONAL, new TreeSet<>(Set.of("dependency")), Set.of(), new TreeMap<>());
		GroupManifest manifest = manifest(Map.of(), Map.of("dependency", dependency, "feature", feature));

		SelectionResolutionException failure = assertThrows(SelectionResolutionException.class,
				() -> GroupSelectionResolver.resolve(manifest, SelectionIntent.ofEnabled(Set.of("feature")), ClientPlatform.LINUX));

		assertEquals(Set.of("feature"), failure.resolution().requestedUnavailableGroups());
		assertEquals(GroupResolution.Status.BLOCKED, failure.resolution().resolution("feature").status());
		assertEquals(Set.of("dependency"), failure.resolution().resolution("feature").relatedGroups());
	}

	@Test
	void unsupportedGroupsNeverRequestedRemainOutsideRequestedUnavailableSubset() {
		GroupManifest manifest = manifest(Map.of(),
				Map.of("windows", new GroupManifest.Group("Windows", "", "", GroupSelectionMode.OPTIONAL, new TreeSet<>(), Set.of(ClientPlatform.WINDOWS), new TreeMap<>())));

		ResolvedSelection resolved = GroupSelectionResolver.resolve(manifest, SelectionIntent.DEFAULTS, ClientPlatform.LINUX);

		assertEquals(Set.of("windows"), resolved.unavailableGroups());
		assertTrue(resolved.requestedUnavailableGroups().isEmpty());
	}

	@Test
	void dependencyExplanationAndAutomaticActivationFollowDependents() {
		GroupManifest manifest = manifest(Map.of(),
				Map.of("shader-base", group(GroupSelectionMode.AUTOMATIC, Set.of()), "iris", group(GroupSelectionMode.OPTIONAL, Set.of("shader-base"))));

		assertEquals(Set.of(), GroupSelectionResolver.resolveDefault(manifest, ClientPlatform.LINUX).selectedGroups());

		GroupSelectionResolver.SelectionUpdate enabled = GroupSelectionResolver.toggleGroup(manifest, SelectionIntent.DEFAULTS, "iris", ClientPlatform.LINUX);
		assertEquals(Set.of("iris", "shader-base"), enabled.resolution().selectedGroups());
		assertEquals(Set.of("shader-base"), enabled.resolution().dependencyGroups());
		assertEquals(Set.of("iris"), enabled.resolution().resolution("shader-base").relatedGroups());

		GroupSelectionResolver.SelectionUpdate disabled = GroupSelectionResolver.toggleGroup(manifest, enabled.intent(), "iris", ClientPlatform.LINUX);
		assertEquals(Set.of(), disabled.resolution().selectedGroups());
	}

	@Test
	void uncheckingDependencyCascadesToDependentsAndRequiredGroupRefusesUncheck() {
		GroupManifest manifest = manifest(Map.of(),
				Map.of("main", group(GroupSelectionMode.REQUIRED, Set.of()), "base", group(GroupSelectionMode.OPTIONAL, Set.of()),
						"addon", group(GroupSelectionMode.OPTIONAL, Set.of("base"))));

		SelectionIntent both = SelectionIntent.ofEnabled(Set.of("base", "addon"));
		GroupSelectionResolver.SelectionUpdate turnedOffBase = GroupSelectionResolver.toggleGroup(manifest, both, "base", ClientPlatform.LINUX);
		assertEquals("", turnedOffBase.error());
		assertEquals(Set.of("main"), turnedOffBase.resolution().selectedGroups());
		assertEquals(Set.of("addon"), turnedOffBase.turnedOff());

		GroupSelectionResolver.SelectionUpdate tryMain = GroupSelectionResolver.toggleGroup(manifest, SelectionIntent.DEFAULTS, "main", ClientPlatform.LINUX);
		assertFalse(tryMain.error().isEmpty());
		assertEquals(SelectionIntent.DEFAULTS, tryMain.intent());
	}

	@Test
	void exclusiveCategoriesEnforceAtMostOneAndExactlyOne() {
		GroupManifest manifest = manifest(
				Map.of("shaders", new GroupManifest.Category("Shaders", "", CategoryPickMode.AT_MOST_ONE),
						"minimap", new GroupManifest.Category("Minimap", "", CategoryPickMode.EXACTLY_ONE)),
				Map.of("bsl", categorized(GroupSelectionMode.RECOMMENDED, "shaders"),
						"complementary", categorized(GroupSelectionMode.OPTIONAL, "shaders"),
						"xaero", categorized(GroupSelectionMode.RECOMMENDED, "minimap"),
						"journeymap", categorized(GroupSelectionMode.OPTIONAL, "minimap")));

		ResolvedSelection defaults = GroupSelectionResolver.resolveDefault(manifest, ClientPlatform.LINUX);
		assertEquals(Set.of("bsl", "xaero"), defaults.selectedGroups());

		GroupSelectionResolver.SelectionUpdate switchShader = GroupSelectionResolver.pickInCategory(manifest, SelectionIntent.DEFAULTS, "shaders", "complementary", ClientPlatform.LINUX);
		assertEquals("", switchShader.error());
		assertEquals(Set.of("complementary", "xaero"), switchShader.resolution().selectedGroups());

		GroupSelectionResolver.SelectionUpdate clearShader = GroupSelectionResolver.pickInCategory(manifest, switchShader.intent(), "shaders", "", ClientPlatform.LINUX);
		assertEquals("", clearShader.error());
		assertEquals(Set.of("xaero"), clearShader.resolution().selectedGroups());

		GroupSelectionResolver.SelectionUpdate tryClearMinimap = GroupSelectionResolver.pickInCategory(manifest, clearShader.intent(), "minimap", "", ClientPlatform.LINUX);
		assertFalse(tryClearMinimap.error().isEmpty());
		assertEquals(Set.of("xaero"), tryClearMinimap.resolution().selectedGroups());
	}

	@Test
	void setCategoryAllSelectsOrClearsOnlyCompatibleMembersInAnyCategory() {
		GroupManifest manifest = manifest(Map.of("visuals", new GroupManifest.Category("Visuals", "", CategoryPickMode.ANY)),
				Map.of("one", new GroupManifest.Group("One", "", "visuals", GroupSelectionMode.OPTIONAL, Set.of(), Set.of(ClientPlatform.LINUX), Map.of()),
						"two", new GroupManifest.Group("Two", "", "visuals", GroupSelectionMode.RECOMMENDED, Set.of(), Set.of(ClientPlatform.LINUX), Map.of()),
						"win", new GroupManifest.Group("Win", "", "visuals", GroupSelectionMode.OPTIONAL, Set.of(), Set.of(ClientPlatform.WINDOWS), Map.of())));

		GroupSelectionResolver.SelectionUpdate selectedAll = GroupSelectionResolver.setCategoryAll(manifest, SelectionIntent.DEFAULTS, "visuals", true, ClientPlatform.LINUX);
		assertEquals(Set.of("one", "two"), selectedAll.resolution().selectedGroups());

		GroupSelectionResolver.SelectionUpdate clearedAll = GroupSelectionResolver.setCategoryAll(manifest, selectedAll.intent(), "visuals", false, ClientPlatform.LINUX);
		assertEquals(Set.of(), clearedAll.resolution().selectedGroups());
	}

	@Test
	void declaredOnlyPlatformGroupIsNotAutoMatchedButIsSelectable() {
		ClientPlatform android = ClientPlatform.parse("android");
		GroupManifest.Group mobile = new GroupManifest.Group("Mobile", "", "", GroupSelectionMode.OPTIONAL, new TreeSet<>(), Set.of(android), new TreeMap<>());
		GroupManifest.Group desktop = new GroupManifest.Group("Desktop", "", "", GroupSelectionMode.RECOMMENDED, new TreeSet<>(), Set.of(ClientPlatform.LINUX), new TreeMap<>());
		GroupManifest manifest = manifest(Map.of(), Map.of("mobile", mobile, "desktop", desktop));

		ResolvedSelection defaults = GroupSelectionResolver.resolveDefault(manifest, ClientPlatform.LINUX);
		assertFalse(defaults.selectedGroups().contains("mobile"));
		assertEquals(GroupResolution.Status.UNAVAILABLE, defaults.resolution("mobile").status());
		assertEquals(Set.of("desktop"), defaults.selectedGroups());

		SelectionIntent intent = new SelectionIntent(Set.of("mobile"), Set.of(), android);
		assertTrue(GroupSelectionResolver.resolve(manifest, intent, android).selectedGroups().contains("mobile"));
	}

	@Test
	void noPlatformKeepsOnlyThePlatformAgnosticGroups() {
		GroupManifest.Group any = group(GroupSelectionMode.RECOMMENDED, Set.of());
		GroupManifest.Group windows = new GroupManifest.Group("Windows", "", "", GroupSelectionMode.RECOMMENDED, new TreeSet<>(), Set.of(ClientPlatform.WINDOWS), new TreeMap<>());
		GroupManifest manifest = manifest(Map.of(), Map.of("any", any, "windows", windows));

		ResolvedSelection defaults = GroupSelectionResolver.resolveDefault(manifest, null);
		assertEquals(Set.of("any"), defaults.selectedGroups());
		assertEquals(GroupResolution.Status.UNAVAILABLE, defaults.resolution("windows").status());
	}

	@Test
	void osScopedRequiredGroupInstallsOnlyOnItsPlatformWithoutError() {
		GroupManifest.Group windowsCore = new GroupManifest.Group("Windows Runtime", "", "", GroupSelectionMode.REQUIRED, Set.of(), Set.of(ClientPlatform.WINDOWS), Map.of());
		GroupManifest.Group core = group(GroupSelectionMode.REQUIRED, Set.of());
		GroupManifest manifest = manifest(Map.of(), Map.of("main", core, "win-core", windowsCore));

		ResolvedSelection windows = GroupSelectionResolver.resolveDefault(manifest, ClientPlatform.WINDOWS);
		assertEquals(Set.of("main", "win-core"), windows.selectedGroups());
		assertEquals(Set.of("main", "win-core"), windows.requiredGroups());

		ResolvedSelection linux = GroupSelectionResolver.resolveDefault(manifest, ClientPlatform.LINUX);
		assertEquals(Set.of("main"), linux.selectedGroups());
		assertEquals(Set.of("main"), linux.requiredGroups());
		assertEquals(GroupResolution.Status.UNAVAILABLE, linux.resolution("win-core").status());
		assertTrue(linux.unavailableGroups().contains("win-core"));
	}

	@Test
	void pairwiseConflictsAreSymmetricInResolutionAndPossibleToDeclareFromEitherSide() {
		GroupManifest.Group audio = new GroupManifest.Group("", "", "", GroupSelectionMode.OPTIONAL, Set.of(), Set.of(), Map.of(), Set.of("zoom"));
		GroupManifest.Group zoom = new GroupManifest.Group("", "", "", GroupSelectionMode.OPTIONAL, Set.of(), Set.of(), Map.of(), Set.of());
		GroupManifest manifest = manifest(Map.of(), Map.of("audio", audio, "zoom", zoom));

		assertTrue(manifest.conflicts("audio", "zoom"));
		assertTrue(manifest.conflicts("zoom", "audio"));
		assertEquals(Set.of("zoom"), manifest.conflictsOf("audio"));
		assertEquals(Set.of("audio"), manifest.conflictsOf("zoom"));

		SelectionResolutionException failure = assertThrows(SelectionResolutionException.class,
				() -> GroupSelectionResolver.resolve(manifest, SelectionIntent.ofEnabled(Set.of("audio", "zoom")), ClientPlatform.LINUX));
		assertTrue(failure.errors().contains("Groups 'audio' and 'zoom' conflict with each other"), failure.errors().toString());
		for (String id : List.of("audio", "zoom")) {
			assertEquals(GroupResolution.Status.CONFLICT, failure.resolution().resolution(id).status());
			assertTrue(failure.resolution().resolution(id).reasons().contains(GroupResolution.Reason.PAIRWISE_CONFLICT));
		}
		assertEquals(Set.of("zoom"), failure.resolution().resolution("audio").relatedGroups());
		assertEquals(Set.of("audio"), failure.resolution().resolution("zoom").relatedGroups());
	}

	@Test
	void enablingAGroupTurnsOffTheConflictingChoosableGroup() {
		GroupManifest.Group audio = new GroupManifest.Group("Ambient Sounds", "", "", GroupSelectionMode.OPTIONAL, Set.of(), Set.of(), Map.of(), Set.of("zoom"));
		GroupManifest.Group zoom = new GroupManifest.Group("Zoomify", "", "", GroupSelectionMode.RECOMMENDED, Set.of(), Set.of(), Map.of());
		GroupManifest manifest = manifest(Map.of(), Map.of("audio", audio, "zoom", zoom));

		ResolvedSelection defaults = GroupSelectionResolver.resolveDefault(manifest, ClientPlatform.LINUX);
		assertEquals(Set.of("zoom"), defaults.selectedGroups());

		GroupSelectionResolver.SelectionUpdate enabled = GroupSelectionResolver.toggleGroup(manifest, SelectionIntent.DEFAULTS, "audio", ClientPlatform.LINUX);
		assertEquals("", enabled.error());
		assertEquals(Set.of("audio"), enabled.resolution().selectedGroups());
		assertEquals(Set.of("zoom"), enabled.intent().disabled());
		assertEquals(Set.of("zoom"), enabled.turnedOff());
	}

	@Test
	void conflictWithRequiredGroupRefusesTheToggleInsteadOfDroppingTheRequiredGroup() {
		GroupManifest.Group core = group(GroupSelectionMode.REQUIRED, Set.of());
		GroupManifest.Group audio = new GroupManifest.Group("Ambient Sounds", "", "", GroupSelectionMode.OPTIONAL, Set.of(), Set.of(), Map.of(), Set.of("main"));
		GroupManifest manifest = manifest(Map.of(), Map.of("main", core, "audio", audio));

		GroupSelectionResolver.SelectionUpdate refused = GroupSelectionResolver.toggleGroup(manifest, SelectionIntent.DEFAULTS, "audio", ClientPlatform.LINUX);
		assertFalse(refused.error().isEmpty());
		assertTrue(refused.error().contains("Ambient Sounds") && refused.error().contains("required"), refused.error());
		assertEquals(SelectionIntent.DEFAULTS, refused.intent());
		assertEquals(Set.of("main"), refused.resolution().selectedGroups());
	}

	@Test
	void toggleDisablesDependentOfConflictingAutomaticAndSetCategoryAllClearsExternalConflict() {
		GroupManifest.Group core = new GroupManifest.Group("Core", "", "", GroupSelectionMode.REQUIRED, Set.of(), Set.of(), Map.of(), Set.of());
		GroupManifest.Group engine = new GroupManifest.Group("Engine", "", "", GroupSelectionMode.AUTOMATIC, Set.of(), Set.of(), Map.of(), Set.of());
		GroupManifest.Group shaders = new GroupManifest.Group("Shaders", "", "", GroupSelectionMode.RECOMMENDED, Set.of("engine"), Set.of(), Map.of(), Set.of());
		GroupManifest.Group lite = new GroupManifest.Group("Lite Mode", "", "extras", GroupSelectionMode.OPTIONAL, Set.of(), Set.of(), Map.of(), Set.of("engine"));
		GroupManifest manifest = manifest(Map.of("extras", new GroupManifest.Category("Extras", "", CategoryPickMode.ANY)),
				Map.of("main", core, "engine", engine, "shaders", shaders, "lite", lite));

		GroupSelectionResolver.SelectionUpdate toggled = GroupSelectionResolver.toggleGroup(manifest, SelectionIntent.DEFAULTS, "lite", ClientPlatform.LINUX);
		assertTrue(toggled.ok(), toggled.error());
		assertEquals(Set.of("main", "lite"), toggled.resolution().selectedGroups());
		assertEquals(Set.of("shaders"), toggled.turnedOff());
		assertTrue(toggled.turnedOffByConflict());

		GroupSelectionResolver.SelectionUpdate bulk = GroupSelectionResolver.setCategoryAll(manifest, SelectionIntent.DEFAULTS, "extras", true, ClientPlatform.LINUX);
		assertTrue(bulk.ok(), bulk.error());
		assertEquals(Set.of("main", "lite"), bulk.resolution().selectedGroups());
		assertEquals(Set.of("shaders"), bulk.turnedOff());
		assertTrue(bulk.turnedOffByConflict());
	}

	private static GroupManifest manifest(Map<String, GroupManifest.Category> categories, Map<String, GroupManifest.Group> groups) {
		return new GroupManifest("abc1234", "", "", "", "", "", new LinkedHashMap<>(categories), new LinkedHashMap<>(groups));
	}

	private static GroupManifest.Group group(GroupSelectionMode selection, Set<String> requires) {
		return new GroupManifest.Group("", "", "", selection, new TreeSet<>(requires), Set.of(), new TreeMap<>());
	}

	private static GroupManifest.Group categorized(GroupSelectionMode selection, String category) {
		return new GroupManifest.Group("", "", category, selection, new TreeSet<>(), Set.of(), new TreeMap<>());
	}
}
