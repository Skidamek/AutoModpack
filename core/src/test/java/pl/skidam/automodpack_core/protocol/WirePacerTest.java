package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** The window's dynamics are the pacer's whole contract: lane-depth start, doubling while throughput climbs, plateau freeze, failure halving, hard cap. */
class WirePacerTest {
	/** One virtual millisecond per settle: a deterministic time base for the throughput ratios. */
	private static final AtomicLong TICK = new AtomicLong();

	private WirePacer pacer(int windowCap, int telemetryLanes) {
		TICK.set(0);
		return new WirePacer(windowCap, telemetryLanes, () -> TICK.incrementAndGet() * 1_000_000L);
	}

	/** One full cycle of good settles; each item reports quadrupling bytes so the throughput climb is unambiguous. */
	private void climbCycle(WirePacer pacer, long baseBytes) {
		int settles = pacer.window();
		for (int i = 0; i < settles; i++) pacer.settle(false, baseBytes * (i + 1), 1_000_000, i % 5);
	}

	@Test
	void windowStartsAtOneLanePipelineAndDoublesToTheCap() {
		WirePacer pacer = pacer(8, 2);
		// 2 lanes × depth 4: the floor is one lane's pipeline, the smallest window that can still fill a lane.
		assertEquals(4, pacer.window());
		for (int i = 0; i < 10; i++) climbCycle(pacer, 1_000_000L * (i + 1));
		assertEquals(8, pacer.window());
	}

	@Test
	void fallingThroughputFreezesTheWindowAtThePlateau() {
		WirePacer pacer = pacer(40, 5);
		climbCycle(pacer, 1_000_000);
		assertEquals(16, pacer.window());
		// A cycle moving about the previous cycle's bytes is a plateau: growth freezes, nothing halves.
		flatCycle(pacer, 2_000_000);
		assertEquals(16, pacer.window());
		flatCycle(pacer, 2_000_000);
		assertEquals(16, pacer.window());
	}

	@Test
	void collapsedThroughputHalvesTheWindow() {
		WirePacer pacer = pacer(40, 5);
		climbCycle(pacer, 1_000_000);
		assertEquals(16, pacer.window());
		// A congested wire: the cycle moved a fraction of the previous one's bytes, so the window halves.
		flatCycle(pacer, 1);
		assertEquals(8, pacer.window());
	}

	/** One full cycle of good settles at a constant per-item size, so no cycle can look like a climb. */
	private void flatCycle(WirePacer pacer, long bytes) {
		for (int i = 0; i < pacer.window(); i++) pacer.settle(false, bytes, 1_000_000, i % 5);
	}

	@Test
	void failureHalvesTheWindowAndNeverBelowOne() {
		WirePacer pacer = pacer(40, 5);
		climbCycle(pacer, 1_000_000);
		assertEquals(16, pacer.window());
		pacer.settle(true, 0, 1_000_000, 0);
		assertEquals(8, pacer.window());
		pacer.settle(true, 0, 1_000_000, 0);
		assertEquals(4, pacer.window());
		for (int i = 0; i < 5; i++) pacer.settle(true, 0, 1_000_000, 0);
		assertEquals(1, pacer.window());
	}

	@Test
	void windowAccountingBalancesAcrossAcquireReleaseAndSettle() {
		WirePacer pacer = pacer(4, 4);
		assertEquals(1, pacer.window());
		assertTrue(pacer.hasRoom());
		assertTrue(pacer.tryAcquire());
		assertFalse(pacer.hasRoom());
		assertFalse(pacer.tryAcquire());
		pacer.release();
		assertTrue(pacer.hasRoom());
		assertTrue(pacer.tryAcquire());
		assertEquals(1, pacer.inFlight());
		pacer.settle(false, 10, 1_000_000, 0);
		assertEquals(0, pacer.inFlight());
		assertTrue(pacer.tryAcquire());
	}
}
