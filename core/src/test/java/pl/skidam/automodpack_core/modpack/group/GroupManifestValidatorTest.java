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
		fields.categories = linkedCategories("General", linkedGroups("visuals", group(file("a")), "main", group(file("a"))));
		GroupManifest manifest = GroupManifestValidator.validate(fields);
		assertEquals(List.of("visuals", "main"), new ArrayList<>(manifest.groups().keySet()));
		assertEquals(ConfigTools.GSON.toJson(manifest.toFields()), ConfigTools.GSON.toJson(GroupManifestValidator.validate(manifest.toFields()).toFields()));
	}

	@Test
	void validatesCategoryNameAndRoundTrips() {
		var fields = catalogue();
		fields.categories = linkedCategories("Visuals", linkedGroups("main", group(file("a"))));

		GroupManifest manifest = GroupManifestValidator.validate(fields);

		assertEquals("Visuals", manifest.groups().get("main").category());
		assertEquals(ConfigTools.GSON.toJson(manifest.toFields()), ConfigTools.GSON.toJson(GroupManifestValidator.validate(manifest.toFields()).toFields()));
	}

	@Test
	void everyValidatedGroupCarriesItsCategoryName() {
		var fields = catalogue();
		fields.categories = linkedCategories("General", linkedGroups("main", group(file("a"))));

		GroupManifest manifest = GroupManifestValidator.validate(fields);

		assertEquals("General", manifest.groups().get("main").category());
	}

	@Test
	void rejectsEmptyCategory() {
		var fields = catalogue();
		fields.categories = linkedCategories("General", linkedGroups("main", group(file("a"))), "Empty", linkedGroups());

		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsDuplicateGroupIdsAcrossCategories() {
		var fields = catalogue();
		fields.categories = linkedCategories("General", linkedGroups("main", group(file("a"))), "Visuals", linkedGroups("main", group(file("a"))));

		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsCaseInsensitiveDuplicateCategoryNames() {
		var fields = catalogue();
		fields.categories = linkedCategories("General", linkedGroups("main", group(file("a"))), "general", linkedGroups("other", group(file("a"))));

		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsBlankPaddedControlCharacterAndOverlongCategoryNames() {
		for (String name : List.of("", "   ", " padded", "control\u0007char", "x".repeat(65))) {
			var fields = catalogue();
			fields.categories = linkedCategories(name, linkedGroups("main", group(file("a"))));
			assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields), name);
		}
	}

	@Test
	void preservesDeclarationOrderThroughValidateAndSerializeRoundTrip() {
		var fields = catalogue();
		fields.categories = linkedCategories("Visuals", linkedGroups("z-shaders", group(file("a")), "a-packs", group(file("a"))), "General", linkedGroups("main", group(file("a"))));

		GroupManifest manifest = GroupManifestValidator.validate(fields);

		assertEquals(List.of("Visuals", "General"), manifest.groups().values().stream().map(GroupManifest.Group::category).distinct().toList());
		assertEquals(List.of("z-shaders", "a-packs", "main"), new ArrayList<>(manifest.groups().keySet()));
		var roundTripped = GroupManifestValidator.validate(manifest.toFields()).toFields();
		assertEquals(List.of("Visuals", "General"), new ArrayList<>(roundTripped.categories.keySet()));
		assertEquals(List.of("z-shaders", "a-packs"), new ArrayList<>(roundTripped.categories.get("Visuals").keySet()));
		assertEquals(List.of("main"), new ArrayList<>(roundTripped.categories.get("General").keySet()));
		var parsed = ConfigTools.parse(ConfigTools.GSON.toJson(fields), ModpackJsons.CompleteModpackContentFields.class);
		assertEquals(List.of("Visuals", "General"), new ArrayList<>(parsed.categories.keySet()));
		assertEquals(List.of("z-shaders", "a-packs", "main"), parsed.categories.values().stream().flatMap(category -> category.keySet().stream()).toList());
	}

	@Test
	void rejectsDifferentCoSelectableVariant() {
		var fields = catalogue();
		fields.categories = oneCategory("main", group(file("a")), "visuals", group(file("b")));
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void acceptsDifferentMutuallyExclusiveVariant() {
		var fields = catalogue();
		var first = group(file("a"));
		var second = group(file("b"));
		first.breaksWith = Set.of("second");
		fields.categories = oneCategory("first", first, "second", second);
		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsConflictingDefaultSelections() {
		var fields = catalogue();
		var first = group(file("a"));
		var second = group(file("b"));
		first.defaultSelected = true;
		second.defaultSelected = true;
		first.breaksWith = Set.of("second");
		fields.categories = oneCategory("first", first, "second", second);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsGroupThatConflictsWithForcedGroup() {
		var fields = catalogue();
		var forced = group(file("a"));
		var optional = group(file("b"));
		forced.required = true;
		optional.breaksWith = Set.of("forced");
		fields.categories = oneCategory("forced", forced, "optional", optional);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void acceptsOptionalGroupBlockedByUnavailableDependency() {
		var fields = catalogue();
		var dependency = group(file("a"));
		dependency.compatiblePlatforms = Set.of("windows");
		var optional = group(file("a"));
		optional.requires = Set.of("dependency");
		fields.categories = oneCategory("dependency", dependency, "optional", optional);

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsRequiredOrForcedGroupBlockedByUnavailableDependency() {
		var requiredFields = catalogue();
		var requiredDependency = group(file("a"));
		requiredDependency.compatiblePlatforms = Set.of("windows");
		var required = group(file("a"));
		required.required = true;
		required.requires = Set.of("dependency");
		requiredFields.categories = oneCategory("dependency", requiredDependency, "required", required);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(requiredFields));

		var forcedFields = catalogue();
		var forcedDependency = group(file("a"));
		forcedDependency.compatiblePlatforms = Set.of("windows");
		var forced = group(file("a"));
		forced.required = true;
		forced.requires = Set.of("dependency");
		forcedFields.categories = oneCategory("dependency", forcedDependency, "forced", forced);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(forcedFields));
	}

	@Test
	void rejectsPlatformIndependentAbsolutePath() {
		var fields = catalogue();
		var group = group(file("a"));
		group.files = Map.of("C:/escape.jar", file("a"));
		fields.categories = oneCategory("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsDriveQualifiedPath() {
		var fields = catalogue();
		var group = group(file("a"));
		group.files = Map.of("C:escape.jar", file("a"));
		fields.categories = oneCategory("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsCaseVariantOfReservedMetadataPath() {
		var fields = catalogue();
		var group = group(file("a"));
		group.files = Map.of("AUTOMODPACK-CONTENT.JSON", file("a"));
		fields.categories = oneCategory("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsInvalidGraphAndUnsafePath() {
		var fields = catalogue();
		var group = group(file("a"));
		group.requires = Set.of("missing");
		group.files = Map.of("../escape", file("a"));
		fields.categories = oneCategory("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsReservedRootsAndInvalidTypePathCombinations() {
		for (var invalid : List.of(
				Map.entry("saves/world.dat", "other"),
				Map.entry("logs/latest.log", "other"),
				Map.entry("screenshots/image.png", "other"),
				Map.entry("config/settings.json", "other"),
				Map.entry("shaderpacks/shader.zip", "other"),
				Map.entry("resourcepacks/pack.zip", "other"),
				Map.entry("options.txt", "other"))) {
			var fields = catalogue();
			fields.categories = oneCategory("main", groupAt(invalid.getKey(), fileOfType(invalid.getValue())));
			assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields), invalid.getKey());
		}
	}

	@Test
	void acceptsModFilesAtAnyNonReservedPath() {
		for (String path : List.of("mods/example.jar", "resourcepacks/example.jar", "shaderpacks/example.jar", "config/example.jar", "outside/example.jar")) {
			var fields = catalogue();
			fields.categories = oneCategory("main", groupAt(path, fileOfType("mod")));
			assertDoesNotThrow(() -> GroupManifestValidator.validate(fields), path);
		}
	}

	@Test
	void acceptsGenericFilesInsideMods() {
		var fields = catalogue();
		fields.categories = oneCategory("main", groupAt("mods/README.txt", fileOfType("other")));

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsReservedRootFilesAndCaseVariantsForMods() {
		for (String path : List.of("mods", "config", "shaderpacks", "resourcepacks", "MODS/example.jar", "Config/example.jar", "ShaderPacks/example.jar",
				"ResourcePacks/example.jar")) {
			var fields = catalogue();
			fields.categories = oneCategory("main", groupAt(path, fileOfType("mod")));
			assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields), path);
		}
	}

	@Test
	void rejectsCoSelectableModsThatShareALiveBasename() {
		var fields = catalogue();
		fields.categories = oneCategory("main", groupAt("mods/main.jar", fileOfType("mod")), "visuals", groupAt("mods/nested/main.jar", fileOfType("mod")));

		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsFileDirectoryConflictWithinOneGroup() {
		var fields = catalogue();
		var group = group(fileOfType("mod"));
		group.files = Map.of("outside", fileOfType("mod"), "outside/nested.jar", fileOfType("mod"));
		fields.categories = oneCategory("main", group);

		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsCoSelectableFileDirectoryConflict() {
		var fields = catalogue();
		fields.categories = oneCategory("main", groupAt("outside", fileOfType("mod")), "visuals", groupAt("outside/nested.jar", fileOfType("mod")));

		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void prefixWalkReportsEveryAncestorConflictOfANestedAndAliasedCatalogue() {
		var fields = catalogue();
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
		exclusive.compatiblePlatforms = Set.of("linux");
		exclusive.breaksWith = Set.of("leaf");
		var leaf = groupAt("outside/e/f.jar", fileOfType("mod"));
		leaf.compatiblePlatforms = Set.of("linux");
		fields.categories = oneCategory("trunk", trunk, "fork1", fork1, "fork2", fork2, "fork3", fork3, "exclusive", exclusive, "leaf", leaf);

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
	void acceptsFileDirectoryPathsForMutuallyExclusiveGroups() {
		var fields = catalogue();
		var first = groupAt("outside", fileOfType("mod"));
		first.breaksWith = Set.of("second");
		fields.categories = oneCategory("first", first, "second", groupAt("outside/nested.jar", fileOfType("mod")));

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void acceptsSameModBasenameOutsideLiveModsDirectory() {
		var fields = catalogue();
		fields.categories = oneCategory("main", groupAt("mods/main.jar", fileOfType("mod")), "resourcepack", groupAt("resourcepacks/main.jar", fileOfType("mod")),
				"shaderpack", groupAt("shaderpacks/main.jar", fileOfType("mod")));

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsWindowsIllegalAndReservedComponents() {
		var fields = catalogue();
		var group = group(file("a"));
		group.compatiblePlatforms = Set.of("windows");
		group.files = Map.of("mods/CON.txt", file("a"), "config/bad?.json", file("a"));
		fields.categories = oneCategory("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsCaseAliasForWindowsButAllowsItForLinuxOnly() {
		var windows = catalogue();
		var windowsGroup = group(file("a"));
		windowsGroup.compatiblePlatforms = Set.of("windows");
		windowsGroup.files = Map.of("mods/A.jar", file("a"), "mods/a.jar", file("a"));
		windows.categories = oneCategory("main", windowsGroup);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(windows));

		var linux = catalogue();
		var linuxGroup = group(file("a"));
		linuxGroup.compatiblePlatforms = Set.of("linux");
		linuxGroup.files = Map.of("mods/A.jar", file("a"), "mods/a.jar", file("a"));
		linux.categories = oneCategory("main", linuxGroup);
		assertDoesNotThrow(() -> GroupManifestValidator.validate(linux));
	}

	@Test
	void acceptsConflictingGroupsInOneCategoryUntilRequestedTogether() {
		var fields = catalogue();
		var first = group(file("a"));
		first.breaksWith = Set.of("second");
		var second = group(file("a"));
		fields.categories = linkedCategories("bundle", linkedGroups("first", first, "second", second));

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsDifferentSamePathContentInsideOneCategoryBundle() {
		var fields = catalogue();
		var first = group(file("a"));
		var second = group(file("b"));
		fields.categories = linkedCategories("bundle", linkedGroups("first", first, "second", second));

		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void acceptsCategoryWithDependencyConflictUntilRequestedTogether() {
		var fields = catalogue();
		var dependency = group(file("a"));
		var first = group(file("a"));
		first.requires = Set.of("dependency");
		var second = group(file("a"));
		second.breaksWith = Set.of("dependency");
		fields.categories = linkedCategories("bundle", linkedGroups("first", first, "second", second), "General", linkedGroups("dependency", dependency));

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void acceptsAdminDeclaredPlatformAndRejectsBlankNames() {
		var fields = catalogue();
		var group = group(file("a"));
		group.compatiblePlatforms = Set.of("Android");
		fields.categories = oneCategory("main", group);

		GroupManifest manifest = GroupManifestValidator.validate(fields);

		assertTrue(manifest.groups().get("main").supports(ClientPlatform.parse("android")));
		assertFalse(manifest.groups().get("main").supports(ClientPlatform.LINUX));

		var blank = catalogue();
		var blankGroup = group(file("a"));
		blankGroup.compatiblePlatforms = Set.of("   ");
		blank.categories = oneCategory("main", blankGroup);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(blank));
	}

	@Test
	void validatesRulesForDeclaredOnlyPlatforms() {
		var fields = catalogue();
		var app = group(file("a"));
		app.required = true;
		app.compatiblePlatforms = Set.of("android");
		app.requires = Set.of("win-lib");
		var winLib = group(file("a"));
		winLib.compatiblePlatforms = Set.of("windows");
		fields.categories = oneCategory("app", app, "win-lib", winLib);

		// No detectable platform can see this graph, so only the declared android coverage can reject the impossible requirement.
		GroupValidationException failure = assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
		assertTrue(failure.getMessage().contains("android"), failure.getMessage());
	}

	private static ModpackJsons.CompleteModpackContentFields catalogue() {
		var fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.categories = new LinkedHashMap<>();
		return fields;
	}

	private static ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group(ModpackJsons.CompleteModpackContentFields.GroupFileFields file) {
		return groupAt("mods/example.jar", file);
	}

	private static ModpackJsons.CompleteModpackContentFields.ModpackGroupFields groupAt(String path, ModpackJsons.CompleteModpackContentFields.GroupFileFields file) {
		var group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
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
		return new ModpackJsons.CompleteModpackContentFields.GroupFileFields("1", type, false, hash, null);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, ModpackJsons.CompleteModpackContentFields.ModpackGroupFields> linkedGroups(Object... values) {
		Map<String, ModpackJsons.CompleteModpackContentFields.ModpackGroupFields> groups = new LinkedHashMap<>();
		for (int i = 0; i < values.length; i += 2) groups.put((String) values[i], (ModpackJsons.CompleteModpackContentFields.ModpackGroupFields) values[i + 1]);
		return groups;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Map<String, ModpackJsons.CompleteModpackContentFields.ModpackGroupFields>> linkedCategories(Object... values) {
		Map<String, Map<String, ModpackJsons.CompleteModpackContentFields.ModpackGroupFields>> categories = new LinkedHashMap<>();
		for (int i = 0; i < values.length; i += 2)
			categories.put((String) values[i], (Map<String, ModpackJsons.CompleteModpackContentFields.ModpackGroupFields>) values[i + 1]);
		return categories;
	}

	private static Map<String, Map<String, ModpackJsons.CompleteModpackContentFields.ModpackGroupFields>> oneCategory(Object... values) {
		return linkedCategories("General", linkedGroups(values));
	}
}
