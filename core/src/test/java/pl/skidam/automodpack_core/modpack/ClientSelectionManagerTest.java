package pl.skidam.automodpack_core.modpack;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.PlatformUtils;

class ClientSelectionManagerTest {

	private static Jsons.ModpackContentFields.ModpackGroupFields group(boolean required, boolean recommended, String... files) {
		var group = new Jsons.ModpackContentFields.ModpackGroupFields();
		group.required = required;
		group.recommended = recommended;
		group.files = new HashSet<>(List.of(files));
		return group;
	}

	private static Map<String, Jsons.ModpackContentFields.ModpackGroupFields> groups(Object... idsAndGroups) {
		Map<String, Jsons.ModpackContentFields.ModpackGroupFields> map = new LinkedHashMap<>();
		for (int i = 0; i < idsAndGroups.length; i += 2) {
			map.put((String) idsAndGroups[i], (Jsons.ModpackContentFields.ModpackGroupFields) idsAndGroups[i + 1]);
		}
		return map;
	}

	@Test
	void defaultsToRequiredAndRecommended() {
		var map = groups("core", group(true, false), "perf", group(false, true), "shaders", group(false, false));

		assertEquals(Set.of("core", "perf"), ClientSelectionManager.defaultSelection(map));
	}

	@Test
	void requiredGroupsCannotBeDeselected() {
		var map = groups("core", group(true, false), "shaders", group(false, false));

		// The player unticked everything; required content must survive it.
		assertEquals(Set.of("core"), ClientSelectionManager.resolve(map, Set.of()));
	}

	@Test
	void pullsInDependenciesTransitively() {
		var api = group(false, false);
		var lib = group(false, false);
		lib.requires = Set.of("api");
		var mods = group(false, false);
		mods.requires = Set.of("lib");
		var map = groups("api", api, "lib", lib, "mods", mods);

		assertEquals(Set.of("api", "lib", "mods"), ClientSelectionManager.resolve(map, Set.of("mods")));
	}

	@Test
	void dropsGroupWhoseDependencyIsMissingEntirely() {
		var orphan = group(false, false);
		orphan.requires = Set.of("does-not-exist");
		var map = groups("core", group(true, false), "orphan", orphan);

		assertEquals(Set.of("core"), ClientSelectionManager.resolve(map, Set.of("orphan")));
	}

	@Test
	void conflictingGroupsCannotBothBeSelected() {
		var sodium = group(false, false);
		sodium.breaksWith = Set.of("optifine");
		var map = groups("sodium", sodium, "optifine", group(false, false));

		var resolved = ClientSelectionManager.resolve(map, Set.of("sodium", "optifine"));

		assertEquals(1, resolved.size(), "both sides of a conflict were kept");
		assertTrue(resolved.contains("sodium"), "the group declared first should win");
	}

	@Test
	void conflictIsDetectedFromEitherSide() {
		// Only optifine declares the conflict, but it must still be honoured.
		var optifine = group(false, false);
		optifine.breaksWith = Set.of("sodium");
		var map = groups("sodium", group(false, false), "optifine", optifine);

		assertEquals(Set.of("sodium"), ClientSelectionManager.resolve(map, Set.of("sodium", "optifine")));
	}

	@Test
	void requiredGroupWinsConflictAgainstOptional() {
		var optional = group(false, false);
		optional.breaksWith = Set.of("core");
		var map = groups("optional", optional, "core", group(true, false));

		assertEquals(Set.of("core"), ClientSelectionManager.resolve(map, Set.of("optional")));
	}

	@Test
	void dropsDependantWhenItsDependencyLosesAConflict() {
		var shaders = group(false, false);
		shaders.breaksWith = Set.of("core");
		var shaderPacks = group(false, false);
		shaderPacks.requires = Set.of("shaders");
		var map = groups("core", group(true, false), "shaders", shaders, "shaderPacks", shaderPacks);

		// shaders loses to required core, so shaderPacks can no longer stand alone.
		assertEquals(Set.of("core"), ClientSelectionManager.resolve(map, Set.of("shaders", "shaderPacks")));
	}

	@Test
	void excludesGroupsThisOsCannotTake() {
		var incompatible = group(false, true);
		incompatible.compatibleOS = Set.of("!" + PlatformUtils.getCurrentOS().name());
		var compatible = group(false, true);
		compatible.compatibleOS = Set.of(PlatformUtils.getCurrentOS().name());
		var map = groups("nope", incompatible, "yep", compatible);

		assertEquals(Set.of("yep"), ClientSelectionManager.defaultSelection(map));
	}

	@Test
	void selectsOnlyFilesOfChosenGroups() {
		var content = new Jsons.ModpackContentFields();
		content.groups = groups("core", group(true, false, "/mods/core.jar"), "shaders", group(false, false, "/shaders/a.zip"));
		content.list = Set.of(item("/mods/core.jar"), item("/shaders/a.zip"));

		assertEquals(Set.of("/mods/core.jar"), ClientSelectionManager.selectedFiles(content, Set.of("core")));
		assertEquals(Set.of("/mods/core.jar", "/shaders/a.zip"), ClientSelectionManager.selectedFiles(content, Set.of("core", "shaders")));
	}

	@Test
	void keepsFilesThatNoGroupClaims() {
		// A server that never declared groups, or a file added outside them, must still sync.
		var content = new Jsons.ModpackContentFields();
		content.groups = groups("shaders", group(false, false, "/shaders/a.zip"));
		content.list = Set.of(item("/mods/loose.jar"), item("/shaders/a.zip"));

		assertEquals(Set.of("/mods/loose.jar"), ClientSelectionManager.selectedFiles(content, Set.of()));
	}

	@Test
	void groupsAbsentEntirelyMeansEverything() {
		var content = new Jsons.ModpackContentFields();
		content.list = Set.of(item("/mods/a.jar"), item("/mods/b.jar"));

		assertEquals(Set.of("/mods/a.jar", "/mods/b.jar"), ClientSelectionManager.selectedFiles(content, Set.of()));
	}

	private static Jsons.ModpackContentFields.ModpackContentItem item(String file) {
		return new Jsons.ModpackContentFields.ModpackContentItem(file, "1", "mod", false, false, false, "sha", null);
	}
}
