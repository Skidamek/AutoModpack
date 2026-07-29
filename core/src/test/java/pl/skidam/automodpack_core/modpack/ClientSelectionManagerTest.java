package pl.skidam.automodpack_core.modpack;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.Jsons;

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
	void requiredGroupsDependencyWinsConflictOverEarlierOptional() {
		var renderApi = group(false, false);
		var legacyRenderer = group(false, false);
		legacyRenderer.breaksWith = Set.of("renderApi");
		var core = group(true, false);
		core.requires = Set.of("renderApi");
		// legacyRenderer is declared (and chosen) before renderApi, so without dependency-aware
		// priority it would win the conflict on declaration order and get dropped, leaving
		// required "core" with an unsatisfied dependency.
		var map = groups("legacyRenderer", legacyRenderer, "renderApi", renderApi, "core", core);

		assertEquals(Set.of("core", "renderApi"), ClientSelectionManager.resolve(map, Set.of("legacyRenderer")));
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

	@Test
	void filterToSelectionDropsUnselectedFilesButKeepsMetadata() {
		// No saved selection on disk in the test env, so this falls back to required + recommended.
		var content = new Jsons.ModpackContentFields();
		content.modpackId = "abc123";
		content.modpackName = "My Server";
		content.mcVersion = "26.2";
		content.loader = "fabric";
		content.groups = groups("main", group(true, false, "/mods/core.jar"), "extras", group(false, false, "/mods/extra.jar"));
		content.list = Set.of(item("/mods/core.jar"), item("/mods/extra.jar"));

		var filtered = ClientSelectionManager.filterToSelection(content);

		// The optional, non-recommended group is dropped; the required group stays.
		assertEquals(Set.of("/mods/core.jar"), filtered.list.stream().map(fileItem -> fileItem.file).collect(java.util.stream.Collectors.toSet()));
		// Identity fields the update check and download screen rely on must survive filtering.
		assertEquals("abc123", filtered.modpackId);
		assertEquals("My Server", filtered.modpackName);
		assertEquals("26.2", filtered.mcVersion);
		assertEquals("fabric", filtered.loader);
		// Groups are retained so the selection screen still works after a filtered download.
		assertEquals(Set.of("main", "extras"), filtered.groups.keySet());
	}

	@Test
	void filterToSelectionLeavesGrouplessContentUntouched() {
		var content = new Jsons.ModpackContentFields();
		content.list = Set.of(item("/mods/a.jar"));
		assertSame(content, ClientSelectionManager.filterToSelection(content));
	}

	private static Jsons.ModpackContentFields.ModpackContentItem item(String file) {
		return new Jsons.ModpackContentFields.ModpackContentItem(file, "1", "mod", false, false, false, "sha", null);
	}
}
