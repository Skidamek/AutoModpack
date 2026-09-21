package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The window's dynamics are the pacer's whole contract: lane-depth start, doubling every clean cycle to the cap, congestion halving with a floor of 1, permanent verdicts as pure accounting. */
class WirePacerTest {

	/** One full cycle of clean settles at the current window; the bytes are arbitrary, growth is a pure count. */
	private static void okCycle(WirePacer pacer) {
		for (int i = 0, settles = pacer.window(); i < settles; i++) pacer.settle(WirePacer.Verdict.OK, 1_000_000, 1_000_000, i % 5);
	}

	@Test
	void windowStartsAtOneLaneDepthAndDoublesEveryCleanCycleToTheCap() {
		WirePacer pacer = new WirePacer(8, 2);
		// 2 lanes × depth 4: the floor is one lane's pipeline, the smallest window that can still fill a lane.
		assertEquals(4, pacer.window());
		for (int i = 0; i < 10; i++) okCycle(pacer);
		assertEquals(8, pacer.window());
	}

	@Test
	void congestedSettleHalvesTheWindowNeverBelowOne() {
		WirePacer pacer = new WirePacer(40, 5);
		okCycle(pacer);
		assertEquals(16, pacer.window());
		pacer.settle(WirePacer.Verdict.CONGESTED, 0, 1_000_000, 0);
		assertEquals(8, pacer.window());
		pacer.settle(WirePacer.Verdict.CONGESTED, 0, 1_000_000, 0);
		assertEquals(4, pacer.window());
		for (int i = 0; i < 5; i++) pacer.settle(WirePacer.Verdict.CONGESTED, 0, 1_000_000, 0);
		assertEquals(1, pacer.window());
	}

	@Test
	void permanentSettleNeitherHalvesNorAdvancesTheCycle() {
		WirePacer pacer = new WirePacer(40, 5);
		assertEquals(8, pacer.window());
		for (int i = 0; i < 7; i++) pacer.settle(WirePacer.Verdict.OK, 1_000_000, 1_000_000, i % 5);
		pacer.settle(WirePacer.Verdict.PERMANENT, 0, 1_000_000, 0);
		assertEquals(8, pacer.window(), "a permanent verdict is accounting only: the cycle stays one settle short");
		pacer.settle(WirePacer.Verdict.OK, 1_000_000, 1_000_000, 0);
		assertEquals(16, pacer.window(), "the next clean settle completes the cycle");
	}

	@Test
	void cleanCyclesRampTheWindowBackUpAfterAHalving() {
		WirePacer pacer = new WirePacer(40, 5);
		pacer.settle(WirePacer.Verdict.CONGESTED, 0, 1_000_000, 0);
		assertEquals(4, pacer.window());
		okCycle(pacer);
		assertEquals(8, pacer.window());
		okCycle(pacer);
		assertEquals(16, pacer.window());
	}

	@Test
	void windowAccountingBalancesAcrossAcquireReleaseAndSettle() {
		WirePacer pacer = new WirePacer(4, 4);
		assertEquals(1, pacer.window());
		assertTrue(pacer.hasRoom());
		assertTrue(pacer.tryAcquire());
		assertFalse(pacer.hasRoom());
		assertFalse(pacer.tryAcquire());
		pacer.release();
		assertTrue(pacer.hasRoom());
		assertTrue(pacer.tryAcquire());
		assertEquals(1, pacer.inFlight());
		pacer.settle(WirePacer.Verdict.OK, 10, 1_000_000, 0);
		assertEquals(0, pacer.inFlight());
		assertTrue(pacer.tryAcquire());
	}
}
