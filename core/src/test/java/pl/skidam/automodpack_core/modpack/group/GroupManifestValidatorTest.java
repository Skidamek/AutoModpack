package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

class GroupManifestValidatorTest {
	@Test
	void acceptsIdenticalSharedFileAndCanonicalizesOrder() {
		var fields = catalogue();
		fields.groups = linkedGroups("visuals", group(file("a")), "main", group(file("a")));
		GroupManifest manifest = GroupManifestValidator.validate(fields);
		assertEquals(List.of("main", "visuals"), new ArrayList<>(manifest.groups().keySet()));
		assertEquals(ConfigTools.GSON.toJson(manifest.toFields()), ConfigTools.GSON.toJson(GroupManifestValidator.validate(manifest.toFields()).toFields()));
	}

	@Test
	void validatesOptionalTagAndRoundTrips() {
		var fields = catalogue();
		var group = group(file("a"));
		group.tag = "visuals";
		fields.groups = Map.of("main", group);

		GroupManifest manifest = GroupManifestValidator.validate(fields);

		assertEquals("visuals", manifest.groups().get("main").tag());
		assertEquals(ConfigTools.GSON.toJson(manifest.toFields()), ConfigTools.GSON.toJson(GroupManifestValidator.validate(manifest.toFields()).toFields()));
	}

	@Test
	void keepsUntaggedGroupsInTheGeneralSection() {
		var fields = catalogue();
		fields.groups = Map.of("main", group(file("a")));

		GroupManifest manifest = GroupManifestValidator.validate(fields);

		assertEquals("", manifest.groups().get("main").tag());
	}

	@Test
	void rejectsUnsafeOptionalTagValues() {
		for (String value : List.of("Visuals", "tag name", "../tag")) {
			var fields = catalogue();
			var group = group(file("a"));
			group.tag = value;
			fields.groups = Map.of("main", group);
			assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields), value);
		}
	}

	@Test
	void rejectsDifferentCoSelectableVariant() {
		var fields = catalogue();
		fields.groups = linkedGroups("main", group(file("a")), "visuals", group(file("b")));
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void acceptsDifferentMutuallyExclusiveVariant() {
		var fields = catalogue();
		var first = group(file("a"));
		var second = group(file("b"));
		first.breaksWith = Set.of("second");
		fields.groups = linkedGroups("first", first, "second", second);
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
		fields.groups = linkedGroups("first", first, "second", second);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsGroupThatConflictsWithForcedGroup() {
		var fields = catalogue();
		var forced = group(file("a"));
		var optional = group(file("b"));
		forced.required = true;
		optional.breaksWith = Set.of("forced");
		fields.groups = linkedGroups("forced", forced, "optional", optional);
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
	void rejectsRequiredOrForcedGroupBlockedByUnavailableDependency() {
		var requiredFields = catalogue();
		var requiredDependency = group(file("a"));
		requiredDependency.compatiblePlatforms = Set.of("windows");
		var required = group(file("a"));
		required.required = true;
		required.requires = Set.of("dependency");
		requiredFields.groups = linkedGroups("dependency", requiredDependency, "required", required);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(requiredFields));

		var forcedFields = catalogue();
		var forcedDependency = group(file("a"));
		forcedDependency.compatiblePlatforms = Set.of("windows");
		var forced = group(file("a"));
		forced.required = true;
		forced.requires = Set.of("dependency");
		forcedFields.groups = linkedGroups("dependency", forcedDependency, "forced", forced);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(forcedFields));
	}

	@Test
	void rejectsPlatformIndependentAbsolutePath() {
		var fields = catalogue();
		var group = group(file("a"));
		group.files = Map.of("C:/escape.jar", file("a"));
		fields.groups = Map.of("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsDriveQualifiedPath() {
		var fields = catalogue();
		var group = group(file("a"));
		group.files = Map.of("C:escape.jar", file("a"));
		fields.groups = Map.of("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsCaseVariantOfReservedMetadataPath() {
		var fields = catalogue();
		var group = group(file("a"));
		group.files = Map.of("AUTOMODPACK-CATALOGUE.JSON", file("a"));
		fields.groups = Map.of("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsInvalidGraphAndUnsafePath() {
		var fields = catalogue();
		var group = group(file("a"));
		group.requires = Set.of("missing");
		group.files = Map.of("../escape", file("a"));
		fields.groups = Map.of("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsPlayerLocalRootsAndInvalidTypePathCombinations() {
		for (var invalid : List.of(
				Map.entry("saves/world.dat", "other"),
				Map.entry("logs/latest.log", "other"),
				Map.entry("screenshots/image.png", "other"),
				Map.entry("server-resource-packs/pack.zip", "other"),
				Map.entry("mods/readme.txt", "other"),
				Map.entry("config/settings.json", "other"),
				Map.entry("shaderpacks/shader.zip", "other"),
				Map.entry("resourcepacks/pack.zip", "other"),
				Map.entry("options.txt", "other"),
				Map.entry("outside/example.jar", "mod"))) {
			var fields = catalogue();
			fields.groups = Map.of("main", groupAt(invalid.getKey(), fileOfType(invalid.getValue())));
			assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields), invalid.getKey());
		}
	}

	@Test
	void rejectsCoSelectableModsThatShareALiveBasename() {
		var fields = catalogue();
		fields.groups = linkedGroups("main", groupAt("mods/main.jar", fileOfType("mod")), "visuals", groupAt("mods/nested/main.jar", fileOfType("mod")));

		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsWindowsIllegalAndReservedComponents() {
		var fields = catalogue();
		var group = group(file("a"));
		group.compatiblePlatforms = Set.of("windows");
		group.files = Map.of("mods/CON.txt", file("a"), "config/bad?.json", file("a"));
		fields.groups = Map.of("main", group);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsCaseAliasForWindowsButAllowsItForLinuxOnly() {
		var windows = catalogue();
		var windowsGroup = group(file("a"));
		windowsGroup.compatiblePlatforms = Set.of("windows");
		windowsGroup.files = Map.of("mods/A.jar", file("a"), "mods/a.jar", file("a"));
		windows.groups = Map.of("main", windowsGroup);
		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(windows));

		var linux = catalogue();
		var linuxGroup = group(file("a"));
		linuxGroup.compatiblePlatforms = Set.of("linux");
		linuxGroup.files = Map.of("mods/A.jar", file("a"), "mods/a.jar", file("a"));
		linux.groups = Map.of("main", linuxGroup);
		assertDoesNotThrow(() -> GroupManifestValidator.validate(linux));
	}

	@Test
	void acceptsConflictingGroupsInOneCategoryUntilRequestedTogether() {
		var fields = catalogue();
		var first = group(file("a"));
		first.tag = "bundle";
		first.breaksWith = Set.of("second");
		var second = group(file("a"));
		second.tag = "bundle";
		fields.groups = linkedGroups("first", first, "second", second);

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	@Test
	void rejectsDifferentSamePathContentInsideOneTagBundle() {
		var fields = catalogue();
		var first = group(file("a"));
		first.tag = "bundle";
		var second = group(file("b"));
		second.tag = "bundle";
		fields.groups = linkedGroups("first", first, "second", second);

		assertThrows(GroupValidationException.class, () -> GroupManifestValidator.validate(fields));
	}

	@Test
	void acceptsCategoryWithDependencyConflictUntilRequestedTogether() {
		var fields = catalogue();
		var dependency = group(file("a"));
		var first = group(file("a"));
		first.tag = "bundle";
		first.requires = Set.of("dependency");
		var second = group(file("a"));
		second.tag = "bundle";
		second.breaksWith = Set.of("dependency");
		fields.groups = linkedGroups("dependency", dependency, "first", first, "second", second);

		assertDoesNotThrow(() -> GroupManifestValidator.validate(fields));
	}

	private static Jsons.CompleteModpackContentFields catalogue() {
		var fields = new Jsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.groups = Map.of();
		return fields;
	}

	private static Jsons.CompleteModpackContentFields.ModpackGroupFields group(Jsons.CompleteModpackContentFields.GroupFileFields file) {
		return groupAt("mods/example.jar", file);
	}

	private static Jsons.CompleteModpackContentFields.ModpackGroupFields groupAt(String path, Jsons.CompleteModpackContentFields.GroupFileFields file) {
		var group = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		group.files = Map.of(path, file);
		return group;
	}

	private static Jsons.CompleteModpackContentFields.GroupFileFields file(String content) {
		String hash = content.equals("a") ? "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8" : "e9d71f5ee7c92d6dc9e92ffdad17b8bd49418f98";
		return fileOfType("mod", hash);
	}

	private static Jsons.CompleteModpackContentFields.GroupFileFields fileOfType(String type) {
		return fileOfType(type, "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8");
	}

	private static Jsons.CompleteModpackContentFields.GroupFileFields fileOfType(String type, String hash) {
		return new Jsons.CompleteModpackContentFields.GroupFileFields("1", type, false, false, hash, null);
	}

	private static Map<String, Jsons.CompleteModpackContentFields.ModpackGroupFields> linkedGroups(Object... values) {
		Map<String, Jsons.CompleteModpackContentFields.ModpackGroupFields> groups = new LinkedHashMap<>();
		for (int i = 0; i < values.length; i += 2) groups.put((String) values[i], (Jsons.CompleteModpackContentFields.ModpackGroupFields) values[i + 1]);
		return groups;
	}
}
