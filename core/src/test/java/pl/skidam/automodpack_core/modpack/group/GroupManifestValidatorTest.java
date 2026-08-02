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
	void rejectsConflictingRecommendedDefaults() {
		var fields = catalogue();
		var first = group(file("a"));
		var second = group(file("b"));
		first.recommended = true;
		second.recommended = true;
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

	private static Jsons.CompleteModpackContentFields catalogue() {
		var fields = new Jsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.groups = Map.of();
		fields.selectionTags = Map.of();
		return fields;
	}

	private static Jsons.CompleteModpackContentFields.ModpackGroupFields group(Jsons.CompleteModpackContentFields.GroupFileFields file) {
		var group = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		group.files = Map.of("mods/example.jar", file);
		return group;
	}

	private static Jsons.CompleteModpackContentFields.GroupFileFields file(String content) {
		String hash = content.equals("a") ? "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8" : "e9d71f5ee7c92d6dc9e92ffdad17b8bd49418f98";
		return new Jsons.CompleteModpackContentFields.GroupFileFields("1", "mod", false, false, false, hash, null);
	}

	private static Map<String, Jsons.CompleteModpackContentFields.ModpackGroupFields> linkedGroups(Object... values) {
		Map<String, Jsons.CompleteModpackContentFields.ModpackGroupFields> groups = new LinkedHashMap<>();
		for (int i = 0; i < values.length; i += 2) groups.put((String) values[i], (Jsons.CompleteModpackContentFields.ModpackGroupFields) values[i + 1]);
		return groups;
	}
}
