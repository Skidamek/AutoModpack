package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Process identity for the helper's parent wait: a reused pid with a different start is not the launching game. */
class UpdateHelperMainTest {

	@Test
	void parentGoneWhenThePidWasReused() {
		ProcessHandle self = ProcessHandle.current();
		Assumptions.assumeTrue(self.info().startInstant().isPresent());
		long start = self.info().startInstant().orElseThrow().toEpochMilli();
		assertFalse(UpdateHelperMain.parentGone(self, start));
		assertTrue(UpdateHelperMain.parentGone(self, start - 1));
		assertFalse(UpdateHelperMain.parentGone(self, null));
	}
}
