package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.utils.SemanticVersion;

class SelfUpdaterTest {

	@Test
	void unadvertisedPackVersionsFallBackToTheRunningGameVersion() {
		String previous = Constants.MC_VERSION;
		try {
			Constants.MC_VERSION = "1.20.1";
			// A files-only pack cannot switch the game version, so its published emptiness means "match the running game".
			assertEquals("1.20.1", SelfUpdater.lookupMcVersion(""));
			assertEquals("1.20.1", SelfUpdater.lookupMcVersion(null));
			// An advertised version wins: the launcher switch moves the game to it before the updated build runs.
			assertEquals("1.21.1", SelfUpdater.lookupMcVersion("1.21.1"));
		} finally {
			Constants.MC_VERSION = previous;
		}
	}

	@Test
	void floorAllowsUpdatesTowardStable() {
		// Below the floor only forward movement counts: beta.x -> rc.1 -> rc.2 must install so a tester can follow
		// the server through the pre-release series instead of being stuck kicked at every bump.
		assertTrue(SelfUpdater.validUpdate(SemanticVersion.parse("5.0.0-rc.1"), SemanticVersion.parse("5.0.0-beta.3")));
		assertTrue(SelfUpdater.validUpdate(SemanticVersion.parse("5.0.0-rc.2"), SemanticVersion.parse("5.0.0-rc.1")));
		// Reaching the floor itself is allowed.
		assertTrue(SelfUpdater.validUpdate(SemanticVersion.parse("5.0.0"), SemanticVersion.parse("5.0.0-rc.1")));
		// Above the floor the floor never speaks.
		assertTrue(SelfUpdater.validUpdate(SemanticVersion.parse("5.1.0-rc.1"), SemanticVersion.parse("5.0.0")));
	}

	@Test
	void floorStillBlocksLandingBelowStableWithoutProgress() {
		// A stable install never lands on an older pre-release.
		assertFalse(SelfUpdater.validUpdate(SemanticVersion.parse("5.0.0-beta.9"), SemanticVersion.parse("5.0.0")));
		// Below the floor, a sideways or backwards move is refused too.
		assertFalse(SelfUpdater.validUpdate(SemanticVersion.parse("5.0.0-beta.1"), SemanticVersion.parse("5.0.0-rc.1")));
		assertFalse(SelfUpdater.validUpdate(SemanticVersion.parse("5.0.0-rc.1"), SemanticVersion.parse("5.0.0-rc.1")));
		assertFalse(SelfUpdater.validUpdate(SemanticVersion.parse("5.0.0-rc.1"), SemanticVersion.parse("5.0.0-rc.2")));
	}
}
