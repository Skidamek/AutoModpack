package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;

class GroupManifestValidatorTest {
	@Test
	void acceptsIdenticalSharedFileAndPreservesDeclarationOrder() {
		var fields = catalogue();
		fields.groups = linkedGroups("visuals", group(file("a")), "main", group(file("a")));
		GroupManifest manifest = GroupManifestValidator.validate(fields);
		assertEquals(List.of("visuals", "main"), new ArrayList<>(manifest.groups().keySet()));
		assertEquals(ConfigTools.GSON.toJson(manifest.toFields()), ConfigTools.GSON.toJson(GroupManifestValidator.validate(manifest.toFields()).toFields()));
	}

	@Test
	void validatesCategoryAndRoundTrips() {
		var fields = catalogue();
		fields.categories = linkedCategories("visuals", category("Visuals", CategoryPickMode.AT_MOST_ONE));
		var main = group(file("a"));
		main.category = "visuals";
		fields.groups = linkedGroups("main", main);

		GroupManifest manifest = GroupManifestValidator.validate(fields);

		assertEquals("visuals", manifest.groups().get("main").category());
		assertEquals("Visuals", manifest.categoryDisplayName("visuals"));
		assertEquals(ConfigTools.GSON.toJson(manifest.toFields()), ConfigTools.GSON.toJson(GroupManifestValidator.validate(manifest.toFields()).toFields()));
	}

	@Test
	void uncategorizedGroupCarriesEmptyCategory() {
		var fields = catalogue();
		fields.groups = linkedGroups("main", group(file("a")));

		GroupManifest manifest = GroupManifestValidator.validate(fields);

		assertEquals("", manifest.groups().get("main").category());
	}

	@Test
	void rejectsMissingSelectionMode() {
		var fields = catalogue();
		var group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.files = Map.of("mods/example.jar", file("a"));
		fields.groups = linkedGroups("main", group);

		GroupValidationException failure = assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
		assertTrue(failure.getMessage().contains("missing its selection mode"), failure.getMessage());
	}

	@Test
	void rejectsEmptyCategoryAndUnknownGroupCategory() {
		var emptyCat = catalogue();
		emptyCat.categories = linkedCategories("empty", category("Empty", CategoryPickMode.ANY));
		emptyCat.groups = linkedGroups("main", group(file("a")));
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(emptyCat));

		var unknownCat = catalogue();
		var group = group(file("a"));
		group.category = "missing";
		unknownCat.groups = linkedGroups("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(unknownCat));
	}

	@Test
	void rejectsRequiredOrAutomaticGroupInsideExclusiveCategory() {
		for (GroupSelectionMode forbidden : List.of(GroupSelectionMode.REQUIRED, GroupSelectionMode.AUTOMATIC)) {
			var fields = catalogue();
			fields.categories = linkedCategories("shaders", category("Shaders", CategoryPickMode.AT_MOST_ONE));
			var g = group(file("a"));
			g.category = "shaders";
			g.selection = forbidden;
			fields.groups = linkedGroups("shader-a", g);
			assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields), forbidden.name());
		}
	}

	@Test
	void rejectsInvalidIdentifiersAndWindowsDeviceNames() {
		for (String bad : List.of("", "   ", "Main", "padded ", "my.group", "../bad", "con", "nul", "com1", "x".repeat(65))) {
			var badGroup = catalogue();
			badGroup.groups = linkedGroups(bad, group(file("a")));
			assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(badGroup), "group:" + bad);

			var badCategory = catalogue();
			badCategory.categories = linkedCategories(bad, category("Bad", CategoryPickMode.ANY));
			var g = group(file("a"));
			g.category = bad;
			badCategory.groups = linkedGroups("main", g);
			assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(badCategory), "cat:" + bad);
		}
	}

	@Test
	void preservesDeclarationOrderThroughValidateAndSerializeRoundTrip() {
		var fields = catalogue();
		fields.categories = linkedCategories("visuals", category("Visuals", CategoryPickMode.ANY), "extras", category("Extras", CategoryPickMode.ANY));
		var z = group(file("a"));
		z.category = "visuals";
		var a = group(file("a"));
		a.category = "visuals";
		var main = group(file("a"));
		main.category = "extras";
		fields.groups = linkedGroups("z-shaders", z, "a-packs", a, "main", main);

		GroupManifest manifest = GroupManifestValidator.validate(fields);

		assertEquals(List.of("visuals", "extras"), new ArrayList<>(manifest.categories().keySet()));
		assertEquals(List.of("z-shaders", "a-packs", "main"), new ArrayList<>(manifest.groups().keySet()));
		var roundTripped = GroupManifestValidator.validate(manifest.toFields()).toFields();
		assertEquals(List.of("visuals", "extras"), new ArrayList<>(roundTripped.categories.keySet()));
		assertEquals(List.of("z-shaders", "a-packs", "main"), new ArrayList<>(roundTripped.groups.keySet()));
		var parsed = ConfigTools.parse(ConfigTools.GSON.toJson(fields), ModpackJsons.CompleteModpackContentFields.class);
		assertEquals(List.of("visuals", "extras"), new ArrayList<>(parsed.categories.keySet()));
		assertEquals(List.of("z-shaders", "a-packs", "main"), new ArrayList<>(parsed.groups.keySet()));
	}

	@Test
	void rejectsDifferentCoSelectableVariant() {
		var fields = catalogue();
		fields.groups = linkedGroups("main", group(file("a")), "visuals", group(file("b")));
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void acceptsDifferentExclusiveCategoryVariants() {
		var fields = catalogue();
		fields.categories = linkedCategories("shaders", category("Shaders", CategoryPickMode.AT_MOST_ONE));
		var first = group(file("a"));
		first.category = "shaders";
		var second = group(file("b"));
		second.category = "shaders";
		fields.groups = linkedGroups("first", first, "second", second);
		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsConflictingRecommendedSelectionsInExclusiveCategory() {
		var fields = catalogue();
		fields.categories = linkedCategories("shaders", category("Shaders", CategoryPickMode.AT_MOST_ONE));
		var first = group(file("a"));
		first.category = "shaders";
		first.selection = GroupSelectionMode.RECOMMENDED;
		var second = group(file("b"));
		second.category = "shaders";
		second.selection = GroupSelectionMode.RECOMMENDED;
		fields.groups = linkedGroups("first", first, "second", second);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void acceptsOptionalGroupRequiringNonDefaultExclusiveCategoryMember() {
		var fields = catalogue();
		fields.categories = linkedCategories("shaders", category("Shaders", CategoryPickMode.EXACTLY_ONE));
		var bsl = group(file("a"));
		bsl.category = "shaders";
		bsl.selection = GroupSelectionMode.RECOMMENDED;
		var comp = group(file("b"));
		comp.category = "shaders";
		comp.selection = GroupSelectionMode.OPTIONAL;
		var addon = group(file("b"));
		addon.requires = Set.of("comp");
		fields.groups = linkedGroups("bsl", bsl, "comp", comp, "addon", addon);
		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsAutomaticGroupWithDifferentFileWhenCoSelectableViaActivator() {
		var fields = catalogue();
		var auto = groupAt("mods/shared.jar", file("a"));
		auto.selection = GroupSelectionMode.AUTOMATIC;
		var consumer = groupAt("mods/shared.jar", file("b"));
		consumer.requires = Set.of("auto");
		fields.groups = linkedGroups("auto", auto, "consumer", consumer);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsRequiredGroupWithMutualConflictThroughExclusiveCategory() {
		var fields = catalogue();
		fields.categories = linkedCategories("mode", category("Mode", CategoryPickMode.EXACTLY_ONE));
		var main = group(file("a"));
		main.selection = GroupSelectionMode.REQUIRED;
		main.requires = Set.of("mode-a");
		var modeA = group(file("a"));
		modeA.category = "mode";
		modeA.selection = GroupSelectionMode.RECOMMENDED;
		var modeB = group(file("b"));
		modeB.category = "mode";
		modeB.selection = GroupSelectionMode.OPTIONAL;
		main.requires = Set.of("mode-a", "mode-b");
		fields.groups = linkedGroups("main", main, "mode-a", modeA, "mode-b", modeB);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void acceptsOptionalGroupBlockedByUnavailableDependency() {
		var fields = catalogue();
		var dependency = group(file("a"));
		dependency.compatiblePlatforms = Set.of("windows");
		var optional = group(file("a"));
		optional.requires = Set.of("dependency");
		fields.groups = linkedGroups("dependency", dependency, "optional", optional);

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsRequiredGroupBlockedByUnavailableDependency() {
		var requiredFields = catalogue();
		var requiredDependency = group(file("a"));
		requiredDependency.compatiblePlatforms = Set.of("windows");
		var required = group(file("a"));
		required.selection = GroupSelectionMode.REQUIRED;
		required.requires = Set.of("dependency");
		requiredFields.groups = linkedGroups("dependency", requiredDependency, "required", required);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(requiredFields));
	}

	@Test
	void rejectsPlatformIndependentAbsolutePath() {
		var fields = catalogue();
		var group = group(file("a"));
		group.files = Map.of("C:/escape.jar", file("a"));
		fields.groups = linkedGroups("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsDriveQualifiedPath() {
		var fields = catalogue();
		var group = group(file("a"));
		group.files = Map.of("C:escape.jar", file("a"));
		fields.groups = linkedGroups("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsCaseVariantOfReservedMetadataPath() {
		var fields = catalogue();
		var group = group(file("a"));
		group.files = Map.of("AUTOMODPACK-CONTENT.JSON", file("a"));
		fields.groups = linkedGroups("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsInvalidGraphAndUnsafePath() {
		var fields = catalogue();
		var group = group(file("a"));
		group.requires = Set.of("missing");
		group.files = Map.of("../escape", file("a"));
		fields.groups = linkedGroups("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void ignoresALeftoverTypeFieldOnFileMetadata() {
		var file = ConfigTools.GSON.fromJson("{\"size\":\"1\",\"type\":\"other\",\"editable\":false,\"sha1\":\"86f7e437faa5a7fce15d1ddcb9eaeaea377667b8\"}",
				ModpackJsons.CompleteModpackContentFields.GroupFileFields.class);
		var fields = catalogue();
		fields.groups = linkedGroups("main", groupAt("mods/example.jar", file));
		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsReservedRoots() {
		for (String invalid : List.of("saves/world.dat", "logs/latest.log", "screenshots/image.png")) {
			var fields = catalogue();
			fields.groups = linkedGroups("main", groupAt(invalid, fileOfType("other")));
			assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields), invalid);
		}
	}

	@Test
	void acceptsModFilesAtAnyNonReservedPath() {
		for (String path : List.of("mods/example.jar", "resourcepacks/example.jar", "shaderpacks/example.jar", "config/example.jar", "outside/example.jar")) {
			var fields = catalogue();
			fields.groups = linkedGroups("main", groupAt(path, fileOfType("mod")));
			assertDoesNotThrow(() -> GroupManifestValidator.validate(fields), path);
		}
	}

	@Test
	void acceptsGenericFilesInsideMods() {
		var fields = catalogue();
		fields.groups = linkedGroups("main", groupAt("mods/README.txt", fileOfType("other")));

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsReservedRootFilesAndCaseVariantsForMods() {
		for (String path : List.of("mods", "config", "shaderpacks", "resourcepacks", "MODS/example.jar", "Config/example.jar", "ShaderPacks/example.jar",
				"ResourcePacks/example.jar")) {
			var fields = catalogue();
			fields.groups = linkedGroups("main", groupAt(path, fileOfType("mod")));
			assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields), path);
		}
	}

	@Test
	void rejectsCoSelectableModsThatShareALiveBasename() {
		var fields = catalogue();
		fields.groups = linkedGroups("main", groupAt("mods/main.jar", fileOfType("mod")), "visuals", groupAt("mods/nested/main.jar", fileOfType("mod")));

		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsFileDirectoryConflictWithinOneGroup() {
		var fields = catalogue();
		var group = group(fileOfType("mod"));
		group.files = Map.of("outside", fileOfType("mod"), "outside/nested.jar", fileOfType("mod"));
		fields.groups = linkedGroups("main", group);

		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsCoSelectableFileDirectoryConflict() {
		var fields = catalogue();
		fields.groups = linkedGroups("main", groupAt("outside", fileOfType("mod")), "visuals", groupAt("outside/nested.jar", fileOfType("mod")));

		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void prefixWalkReportsEveryAncestorConflictOfANestedAndAliasedCatalogue() {
		var fields = catalogue();
		fields.categories = linkedCategories("pair", category("Pair", CategoryPickMode.AT_MOST_ONE));
		var trunk = group(fileOfType("mod"));
		trunk.compatiblePlatforms = Set.of("linux");
		trunk.files = Map.of("outside/t", fileOfType("mod"), "outside/t/u", fileOfType("mod"), "outside/t/u/v.jar", fileOfType("mod"));
		var fork1 = groupAt("outside/k", fileOfType("mod"));
		fork1.compatiblePlatforms = Set.of("linux");
		var fork2 = groupAt("outside/k", fileOfType("mod"));
		fork2.compatiblePlatforms = Set.of("linux");
		var fork3 = groupAt("outside/k/z.jar", fileOfType("mod"));
		fork3.compatiblePlatforms = Set.of("linux");
		var exclusive = groupAt("outside/e", fileOfType("mod"));
		exclusive.category = "pair";
		exclusive.compatiblePlatforms = Set.of("linux");
		var leaf = groupAt("outside/e/f.jar", fileOfType("mod"));
		leaf.category = "pair";
		leaf.compatiblePlatforms = Set.of("linux");
		fields.groups = linkedGroups("trunk", trunk, "fork1", fork1, "fork2", fork2, "fork3", fork3, "exclusive", exclusive, "leaf", leaf);

		// The aliased key 'outside/k' (two owners) pairs with the descendant once per owner, the chain pairs every
		// ancestor depth once, and the mutually exclusive pair is silent: exactly the pairwise scan's conflict set.
		GroupValidationException failure = assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
		assertEquals(List.of("linux: file path 'outside/k' cannot be an ancestor of 'outside/k/z.jar' in co-selectable groups 'fork1' and 'fork3'",
				"linux: file path 'outside/k' cannot be an ancestor of 'outside/k/z.jar' in co-selectable groups 'fork2' and 'fork3'",
				"linux: file path 'outside/t' cannot be an ancestor of 'outside/t/u' in group 'trunk'",
				"linux: file path 'outside/t' cannot be an ancestor of 'outside/t/u/v.jar' in group 'trunk'",
				"linux: file path 'outside/t/u' cannot be an ancestor of 'outside/t/u/v.jar' in group 'trunk'"), failure.errors());
	}

	@Test
	void acceptsFileDirectoryPathsForExclusiveGroups() {
		var fields = catalogue();
		fields.categories = linkedCategories("pair", category("Pair", CategoryPickMode.AT_MOST_ONE));
		var first = groupAt("outside", fileOfType("mod"));
		first.category = "pair";
		var second = groupAt("outside/nested.jar", fileOfType("mod"));
		second.category = "pair";
		fields.groups = linkedGroups("first", first, "second", second);

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void acceptsSameModBasenameOutsideLiveModsDirectory() {
		var fields = catalogue();
		fields.groups = linkedGroups("main", groupAt("mods/main.jar", fileOfType("mod")), "resourcepack", groupAt("resourcepacks/main.jar", fileOfType("mod")),
				"shaderpack", groupAt("shaderpacks/main.jar", fileOfType("mod")));

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsWindowsIllegalAndReservedComponents() {
		var fields = catalogue();
		var group = group(file("a"));
		group.compatiblePlatforms = Set.of("windows");
		group.files = Map.of("mods/CON.txt", file("a"), "config/bad?.json", file("a"));
		fields.groups = linkedGroups("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsCaseAliasForWindowsButAllowsItForLinuxOnly() {
		var windows = catalogue();
		var windowsGroup = group(file("a"));
		windowsGroup.compatiblePlatforms = Set.of("windows");
		windowsGroup.files = Map.of("mods/A.jar", file("a"), "mods/a.jar", file("a"));
		windows.groups = linkedGroups("main", windowsGroup);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(windows));

		var linux = catalogue();
		var linuxGroup = group(file("a"));
		linuxGroup.compatiblePlatforms = Set.of("linux");
		linuxGroup.files = Map.of("mods/A.jar", file("a"), "mods/a.jar", file("a"));
		linux.groups = linkedGroups("main", linuxGroup);
		assertDoesNotThrow(() -> GroupManifestValidator.validate(linux));
	}

	@Test
	void rejectsDifferentSamePathContentInsideAnyCategory() {
		var fields = catalogue();
		fields.categories = linkedCategories("bundle", category("Bundle", CategoryPickMode.ANY));
		var first = group(file("a"));
		first.category = "bundle";
		var second = group(file("b"));
		second.category = "bundle";
		fields.groups = linkedGroups("first", first, "second", second);

		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsDependencyCrossingBackIntoExclusiveCategoryFromOtherGroup() {
		var fields = catalogue();
		fields.categories = linkedCategories("mode", category("Mode", CategoryPickMode.EXACTLY_ONE));
		var first = group(file("a"));
		first.category = "mode";
		first.selection = GroupSelectionMode.RECOMMENDED;
		var second = group(file("a"));
		second.category = "mode";
		second.selection = GroupSelectionMode.OPTIONAL;
		var wrapper = group(file("a"));
		wrapper.requires = Set.of("second");
		fields.groups = linkedGroups("first", first, "second", second, "wrapper", wrapper);

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void acceptsAdminDeclaredPlatformAndRejectsBlankNames() {
		var fields = catalogue();
		var group = group(file("a"));
		group.compatiblePlatforms = Set.of("Android");
		fields.groups = linkedGroups("main", group);

		GroupManifest manifest = GroupManifestValidator.validate(fields);

		assertTrue(manifest.groups().get("main").supports(ClientPlatform.parse("android")));
		assertFalse(manifest.groups().get("main").supports(ClientPlatform.LINUX));

		var blank = catalogue();
		var blankGroup = group(file("a"));
		blankGroup.compatiblePlatforms = Set.of("   ");
		blank.groups = linkedGroups("main", blankGroup);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(blank));
	}

	@Test
	void validatesRulesForDeclaredOnlyPlatforms() {
		var fields = catalogue();
		var app = group(file("a"));
		app.selection = GroupSelectionMode.REQUIRED;
		app.compatiblePlatforms = Set.of("android");
		app.requires = Set.of("win-lib");
		var winLib = group(file("a"));
		winLib.compatiblePlatforms = Set.of("windows");
		fields.groups = linkedGroups("app", app, "win-lib", winLib);

		// No detectable platform can see this graph, so only the declared android coverage can reject the impossible requirement.
		GroupValidationException failure = assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
		assertTrue(failure.getMessage().contains("android"), failure.getMessage());
	}

	/** A stored pack keeps loading under the rule it was published by; a new one may not ship names a casefolding disk joins. */
	@Test
	void publishingRefusesNamesOnlyABroaderCaseFoldJoinsWhileLoadingKeepsThem() {
		var fields = catalogue();
		var group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.selection = GroupSelectionMode.REQUIRED;
		group.files = Map.of("config/stra\u00DFe.toml", fileOfType("config"), "config/strasse.toml", fileOfType("config"));
		fields.groups = linkedGroups("main", group);

		GroupManifestValidator.validate(fields);
		GroupValidationException failure = assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validateForPublish(fields));
		assertTrue(failure.getMessage().contains("config/stra\u00DFe.toml") && failure.getMessage().contains("config/strasse.toml")
				&& failure.getMessage().contains("case-insensitive filesystems would make them one file"), failure.getMessage());
	}

	@Test
	void theSamePolicyDocumentAlwaysValidatesToTheSameManifest() {
		var fields = catalogue();
		fields.groups = linkedGroups("main", group(file("a")));

		GroupManifest first = GroupManifestValidator.validate(fields);

		assertEquals(first, GroupManifestValidator.validate(fields));
		assertEquals(first.groups(), GroupManifestValidator.validateForPublish(fields).groups(), "publishing validates the same document under the wider rule");
	}

	@Test
	void acceptsOsScopedRequiredGroupOnEveryCoveredPlatform() {
		var fields = catalogue();
		var winCore = groupAt("mods/win-core.jar", file("b"));
		winCore.selection = GroupSelectionMode.REQUIRED;
		winCore.compatiblePlatforms = Set.of("windows");
		winCore.requires = Set.of("win-lib");
		var winLib = groupAt("mods/win-lib.jar", file("c"));
		winLib.compatiblePlatforms = Set.of("windows");
		fields.groups = linkedGroups("main", group(file("a")), "win-core", winCore, "win-lib", winLib);

		GroupManifest manifest = GroupManifestValidator.validate(fields);

		assertTrue(manifest.groups().get("win-core").supports(ClientPlatform.WINDOWS));
		assertFalse(manifest.groups().get("win-core").supports(ClientPlatform.LINUX));
	}

	@Test
	void rejectsSelfConflictAndUnknownConflictTarget() {
		var self = catalogue();
		var selfGroup = group(file("a"));
		selfGroup.conflicts = Set.of("main");
		self.groups = linkedGroups("main", selfGroup);
		GroupValidationException selfFailure = assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(self));
		assertTrue(selfFailure.getMessage().contains("cannot conflict with itself"), selfFailure.getMessage());

		var unknown = catalogue();
		var unknownGroup = group(file("a"));
		unknownGroup.conflicts = Set.of("missing");
		unknown.groups = linkedGroups("main", unknownGroup);
		GroupValidationException unknownFailure = assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(unknown));
		assertTrue(unknownFailure.getMessage().contains("conflicts with missing group 'missing'"), unknownFailure.getMessage());
	}

	@Test
	void acceptsOverlappingFilesBetweenGroupsThatConflict() {
		var fields = catalogue();
		var first = group(file("a"));
		first.conflicts = Set.of("second");
		var second = group(file("b"));
		fields.groups = linkedGroups("first", first, "second", second);

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsTwoConflictingRecommendedDefaults() {
		var fields = catalogue();
		var first = group(file("a"));
		first.selection = GroupSelectionMode.RECOMMENDED;
		first.conflicts = Set.of("second");
		var second = groupAt("mods/second.jar", file("b"));
		second.selection = GroupSelectionMode.RECOMMENDED;
		fields.groups = linkedGroups("first", first, "second", second);

		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void acceptsOptionalGroupThatConflictsWithARecommendedDefault() {
		var fields = catalogue();
		var recommended = group(file("a"));
		recommended.selection = GroupSelectionMode.RECOMMENDED;
		var optional = groupAt("mods/optional.jar", file("b"));
		optional.conflicts = Set.of("recommended");
		fields.groups = linkedGroups("recommended", recommended, "optional", optional);

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void acceptsOptionalWhoseAutomaticDependencyConflictsWithRecommended() {
		var fields = catalogue();
		var core = groupAt("mods/core.jar", file("a"));
		core.selection = GroupSelectionMode.REQUIRED;
		var engine = groupAt("mods/engine.jar", file("a"));
		engine.selection = GroupSelectionMode.AUTOMATIC;
		var recommended = groupAt("mods/recommended.jar", file("a"));
		recommended.selection = GroupSelectionMode.RECOMMENDED;
		recommended.conflicts = Set.of("engine");
		var optional = groupAt("mods/optional.jar", file("b"));
		optional.selection = GroupSelectionMode.OPTIONAL;
		optional.requires = Set.of("engine");
		fields.groups = linkedGroups("main", core, "engine", engine, "recommended", recommended, "optional", optional);

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	private static ModpackJsons.CompleteModpackContentFields catalogue() {
		var fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.groups = new LinkedHashMap<>();
		return fields;
	}

	private static ModpackJsons.CompleteModpackContentFields.CategoryFields category(String name, CategoryPickMode pick) {
		var cat = new ModpackJsons.CompleteModpackContentFields.CategoryFields();
		cat.name = name;
		cat.pick = pick;
		return cat;
	}

	private static ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group(ModpackJsons.CompleteModpackContentFields.GroupFileFields file) {
		return groupAt("mods/example.jar", file);
	}

	private static ModpackJsons.CompleteModpackContentFields.ModpackGroupFields groupAt(String path, ModpackJsons.CompleteModpackContentFields.GroupFileFields file) {
		var group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.selection = GroupSelectionMode.OPTIONAL;
		group.files = Map.of(path, file);
		return group;
	}

	private static ModpackJsons.CompleteModpackContentFields.GroupFileFields file(String content) {
		String hash = content.equals("a") ? "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8" : "e9d71f5ee7c92d6dc9e92ffdad17b8bd49418f98";
		return fileOfType("mod", hash);
	}

	private static ModpackJsons.CompleteModpackContentFields.GroupFileFields fileOfType(String type) {
		return fileOfType(type, "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8");
	}

	private static ModpackJsons.CompleteModpackContentFields.GroupFileFields fileOfType(String type, String hash) {
		return new ModpackJsons.CompleteModpackContentFields.GroupFileFields("1", false, hash, null);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, ModpackJsons.CompleteModpackContentFields.ModpackGroupFields> linkedGroups(Object... values) {
		Map<String, ModpackJsons.CompleteModpackContentFields.ModpackGroupFields> groups = new LinkedHashMap<>();
		for (int i = 0; i < values.length; i += 2) groups.put((String) values[i], (ModpackJsons.CompleteModpackContentFields.ModpackGroupFields) values[i + 1]);
		return groups;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, ModpackJsons.CompleteModpackContentFields.CategoryFields> linkedCategories(Object... values) {
		Map<String, ModpackJsons.CompleteModpackContentFields.CategoryFields> categories = new LinkedHashMap<>();
		for (int i = 0; i < values.length; i += 2) categories.put((String) values[i], (ModpackJsons.CompleteModpackContentFields.CategoryFields) values[i + 1]);
		return categories;
	}
}
