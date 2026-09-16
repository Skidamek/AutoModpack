package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PinnedModsTest {
	@Test
	void normalizesIdsAndDropsReservedAutomodpack() {
		assertEquals(List.of("controlify", "ias"), PinnedMods.normalize(List.of(" Controlify ", "IAS", "automodpack", "controlify")));
	}

	@Test
	void protectsProjectionIdsThatOverlapAPresentPinnedLiveJar() {
		Set<String> protectedIds = PinnedMods.protectedIds(List.of("controlify"), List.of(Set.of("controlify-pro", "controlify"), Set.of("sodium")));
		assertTrue(PinnedMods.protects(protectedIds, Set.of("controlify")));
		assertTrue(PinnedMods.protects(protectedIds, Set.of("controlify-pro")));
		assertFalse(PinnedMods.protects(protectedIds, Set.of("sodium")));
	}

	@Test
	void missingLiveJarDoesNotProtectThePackCopy() {
		assertTrue(PinnedMods.protectedIds(List.of("controlify"), List.of(Set.of("sodium"))).isEmpty());
	}
}
