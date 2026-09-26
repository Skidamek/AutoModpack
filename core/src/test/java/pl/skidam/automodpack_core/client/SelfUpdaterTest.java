package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.Constants;

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
}
