package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.utils.ScreenshotSettler.Frame;
import pl.skidam.automodpack_core.utils.ScreenshotSettler.Observation;

/** Pins the frame sequence the autotest bridge trusts before it takes a screenshot. */
class ScreenshotSettlerTest {
	private final Object target = new Object();
	private final Object other = new Object();
	private final ScreenshotSettler settler = new ScreenshotSettler(target);

	@Test
	void firstFrameAlwaysWaits() {
		assertEquals(Observation.WAIT, settler.observe(new Frame(target, false)));
	}

	@Test
	void twoConsecutiveCleanFramesSettle() {
		settler.observe(new Frame(target, false));
		assertEquals(Observation.SETTLED, settler.observe(new Frame(target, false)));
		assertEquals(Observation.SETTLED, settler.observe(new Frame(target, false)));
	}

	@Test
	void anOverlayKeepsTheSettleWaiting() {
		settler.observe(new Frame(target, false));
		assertEquals(Observation.WAIT, settler.observe(new Frame(target, true)));
		assertEquals(Observation.WAIT, settler.observe(new Frame(target, false)));
		assertEquals(Observation.SETTLED, settler.observe(new Frame(target, false)));
	}

	@Test
	void aScreenChangeResetsTheStreak() {
		settler.observe(new Frame(target, false));
		settler.observe(new Frame(other, false));
		assertEquals(Observation.WAIT, settler.observe(new Frame(target, false)));
		assertEquals(Observation.SETTLED, settler.observe(new Frame(target, false)));
	}

	@Test
	void twoForeignFramesProveTheTargetGone() {
		settler.observe(new Frame(target, false));
		settler.observe(new Frame(other, false));
		assertEquals(Observation.TARGET_GONE, settler.observe(new Frame(other, false)));
	}

	@Test
	void aReturningTargetIsWaitedForNotDeclaredGone() {
		settler.observe(new Frame(other, false));
		assertEquals(Observation.WAIT, settler.observe(new Frame(target, false)));
	}
}
