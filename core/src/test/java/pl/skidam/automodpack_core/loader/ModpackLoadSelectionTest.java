package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ModpackLoadSelectionTest {
	private static final String LIVE_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String PACK_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	private static ModpackLoadSelection.Jar jar(Path path, String logicalPath, String hash, String id) {
		return new ModpackLoadSelection.Jar(path, logicalPath, hash, false, Set.of(id));
	}

	private static List<Path> select(ModpackLoadSelection.Jar... jars) {
		return ModpackLoadSelection.select(List.of(jars), Set.of(), Set.of(), List.of(), List.of()).paths();
	}

	@Test
	void skipsProjectionJarsAlreadyPresentByHash() {
		Path root = Path.of("build", "active", "mods");
		Path sodium = root.resolve("sodium.jar");
		List<Path> selected = ModpackLoadSelection.select(List.of(jar(sodium, "mods/sodium.jar", LIVE_HASH, "sodium")), Set.of(), Set.of(LIVE_HASH),
				List.of(Set.of("sodium")), List.of()).paths();
		assertEquals(List.of(), selected);
	}

	@Test
	void skipsPinnedOverlappingProjectionJarsAndKeepsOthers() {
		Path root = Path.of("build", "active", "mods");
		Path controlify = root.resolve("controlify.jar");
		Path sodium = root.resolve("sodium.jar");
		List<Path> selected = ModpackLoadSelection.select(
				List.of(jar(controlify, "mods/controlify.jar", PACK_HASH, "controlify"), jar(sodium, "mods/sodium.jar", PACK_HASH, "sodium")),
				Set.of(), Set.of(), List.of(Set.of("controlify")), List.of("controlify")).paths();
		assertEquals(List.of(sodium.toAbsolutePath().normalize()), selected);
	}

	@Test
	void listedPinWithoutALiveJarStillLoadsThePackCopy() {
		Path root = Path.of("build", "active", "mods");
		Path controlify = root.resolve("controlify.jar");
		List<Path> selected = ModpackLoadSelection.select(List.of(jar(controlify, "mods/controlify.jar", PACK_HASH, "controlify")), Set.of(), Set.of(),
				List.of(Set.of("sodium")), List.of("controlify")).paths();
		assertEquals(List.of(controlify.toAbsolutePath().normalize()), selected);
	}

	@Test
	void skipsEditableActiveModsWithoutALiveCopy() {
		Path root = Path.of("build", "active", "mods");
		Path sodium = root.resolve("sodium.jar");
		List<Path> selected = select(new ModpackLoadSelection.Jar(sodium, "mods/sodium.jar", PACK_HASH, true, Set.of("sodium")));
		assertEquals(List.of(), selected);
	}

	@Test
	void skipsProjectionJarsWithALiveJarAtTheSamePath() {
		Path root = Path.of("build", "active", "mods");
		Path sodium = root.resolve("sodium.jar");
		List<Path> selected = ModpackLoadSelection.select(List.of(jar(sodium, "mods/sodium.jar", PACK_HASH, "sodium")), Set.of("mods/sodium.jar"), Set.of(LIVE_HASH),
				List.of(), List.of()).paths();
		assertEquals(List.of(), selected);
	}

	@Test
	void namesTheOneFactThatLeftEachProjectionJarOut() {
		Path root = Path.of("build", "active", "mods");

		ModpackLoadSelection.Selection selection = ModpackLoadSelection.select(
				List.of(new ModpackLoadSelection.Jar(root.resolve("editable.jar"), "mods/editable.jar", PACK_HASH, true, Set.of("editable")),
						jar(root.resolve("same-path.jar"), "mods/same-path.jar", PACK_HASH, "same-path"), jar(root.resolve("same-bytes.jar"), "mods/same-bytes.jar", LIVE_HASH, "same-bytes"),
						jar(root.resolve("pinned.jar"), "mods/pinned.jar", PACK_HASH, "pinned")),
				Set.of("mods/same-path.jar"), Set.of(LIVE_HASH), List.of(Set.of("pinned")), List.of("pinned"));

		assertEquals(List.of(), selection.paths());
		assertEquals(List.of(ModpackLoadSelection.Reason.LIVE_PATH, ModpackLoadSelection.Reason.LIVE_HASH, ModpackLoadSelection.Reason.PINNED),
				selection.excluded().stream().filter(excluded -> excluded.reason() != ModpackLoadSelection.Reason.EDITABLE).map(ModpackLoadSelection.Excluded::reason).toList());
		assertEquals(1, selection.excludedBy(ModpackLoadSelection.Reason.EDITABLE));
	}
}
