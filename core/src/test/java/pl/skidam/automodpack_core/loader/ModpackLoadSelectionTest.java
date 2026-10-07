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

	@Test
	void skipsProjectionJarsAlreadyPresentByHash() {
		Path root = Path.of("build", "active", "mods");
		Path sodium = root.resolve("sodium.jar");
		List<Path> selected = ModpackLoadSelection.select(List.of(jar(sodium, "mods/sodium.jar", LIVE_HASH, "sodium")), Set.of(), Set.of(LIVE_HASH),
				List.of(Set.of("sodium")), List.of());
		assertEquals(List.of(), selected);
	}

	@Test
	void skipsPinnedOverlappingProjectionJarsAndKeepsOthers() {
		Path root = Path.of("build", "active", "mods");
		Path controlify = root.resolve("controlify.jar");
		Path sodium = root.resolve("sodium.jar");
		List<Path> selected = ModpackLoadSelection.select(
				List.of(jar(controlify, "mods/controlify.jar", PACK_HASH, "controlify"), jar(sodium, "mods/sodium.jar", PACK_HASH, "sodium")),
				Set.of(), Set.of(), List.of(Set.of("controlify")), List.of("controlify"));
		assertEquals(List.of(sodium.toAbsolutePath().normalize()), selected);
	}

	@Test
	void listedPinWithoutALiveJarStillLoadsThePackCopy() {
		Path root = Path.of("build", "active", "mods");
		Path controlify = root.resolve("controlify.jar");
		List<Path> selected = ModpackLoadSelection.select(List.of(jar(controlify, "mods/controlify.jar", PACK_HASH, "controlify")), Set.of(), Set.of(),
				List.of(Set.of("sodium")), List.of("controlify"));
		assertEquals(List.of(controlify.toAbsolutePath().normalize()), selected);
	}

	@Test
	void skipsEditableActiveModsWithoutALiveCopy() {
		Path root = Path.of("build", "active", "mods");
		Path sodium = root.resolve("sodium.jar");
		List<Path> selected = ModpackLoadSelection.select(List.of(new ModpackLoadSelection.Jar(sodium, "mods/sodium.jar", PACK_HASH, true, Set.of("sodium"))),
				Set.of(), Set.of(), List.of(), List.of());
		assertEquals(List.of(), selected);
	}

	@Test
	void skipsProjectionJarsWithALiveJarAtTheSamePath() {
		Path root = Path.of("build", "active", "mods");
		Path sodium = root.resolve("sodium.jar");
		List<Path> selected = ModpackLoadSelection.select(List.of(jar(sodium, "mods/sodium.jar", PACK_HASH, "sodium")), Set.of("mods/sodium.jar"), Set.of(LIVE_HASH),
				List.of(), List.of());
		assertEquals(List.of(), selected);
	}
}
