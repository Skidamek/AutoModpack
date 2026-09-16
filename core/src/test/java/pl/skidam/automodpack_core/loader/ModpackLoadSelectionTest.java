package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ModpackLoadSelectionTest {
	private static final String LIVE_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String PACK_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	@Test
	void skipsProjectionJarsAlreadyPresentByHash() {
		Path root = Path.of("build", "active", "mods");
		Path sodium = root.resolve("sodium.jar");
		List<Path> selected = ModpackLoadSelection.select(List.of(new ModpackLoadSelection.Jar(sodium, LIVE_HASH, Set.of("sodium"))), Set.of(LIVE_HASH),
				List.of(Set.of("sodium")), List.of());
		assertEquals(List.of(), selected);
	}

	@Test
	void skipsPinnedOverlappingProjectionJarsAndKeepsOthers() {
		Path root = Path.of("build", "active", "mods");
		Path controlify = root.resolve("controlify.jar");
		Path sodium = root.resolve("sodium.jar");
		List<Path> selected = ModpackLoadSelection.select(
				List.of(new ModpackLoadSelection.Jar(controlify, PACK_HASH, Set.of("controlify")), new ModpackLoadSelection.Jar(sodium, PACK_HASH, Set.of("sodium"))),
				Set.of(), List.of(Set.of("controlify")), List.of("controlify"));
		assertEquals(List.of(sodium.toAbsolutePath().normalize()), selected);
	}

	@Test
	void listedPinWithoutALiveJarStillLoadsThePackCopy() {
		Path root = Path.of("build", "active", "mods");
		Path controlify = root.resolve("controlify.jar");
		List<Path> selected = ModpackLoadSelection.select(List.of(new ModpackLoadSelection.Jar(controlify, PACK_HASH, Set.of("controlify"))), Set.of(),
				List.of(Set.of("sodium")), List.of("controlify"));
		assertEquals(List.of(controlify.toAbsolutePath().normalize()), selected);
	}
}
