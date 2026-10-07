package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.utils.DownloadScheduler.QueuedFile;

class DownloadSchedulerTest {

	private static final long MB = 1024L * 1024L;
	private static final long KB = 1024L;
	private static final long SECOND = 1_000_000_000L;

	@Test
	void sendsTheFileToTheMeasuredFastSource() {
		DownloadScheduler scheduler = new DownloadScheduler();
		scheduler.report("fast.example.com", 10 * MB, SECOND);
		scheduler.report("slow.example.com", MB, SECOND);
		QueuedFile<String> file = new QueuedFile<>("file", 100 * MB, List.of("slow.example.com", "fast.example.com"));
		assertEquals("fast.example.com", scheduler.chooseDomain(file, Map.of()));
	}

	@Test
	void turnsAwayASourceOnceItsBacklogCatchesUp() {
		DownloadScheduler scheduler = new DownloadScheduler();
		scheduler.report("fast.example.com", 10 * MB, SECOND);
		scheduler.report("slow.example.com", MB, SECOND);
		QueuedFile<String> file = new QueuedFile<>("file", 10 * MB, List.of("fast.example.com", "slow.example.com"));
		assertEquals("fast.example.com", scheduler.chooseDomain(file, Map.of()));
		assertEquals("slow.example.com", scheduler.chooseDomain(file, Map.of("fast.example.com", 500 * MB)));
	}

	@Test
	void seedsUnknownSourcesOptimisticallyAndConvergesOnSamples() {
		DownloadScheduler scheduler = new DownloadScheduler();
		QueuedFile<String> file = new QueuedFile<>("file", 100 * MB, List.of("tortoise.example.com", "hare.example.com"));
		// Both unknown: equal optimistic seeds, so the caller's preference order decides.
		assertEquals("tortoise.example.com", scheduler.chooseDomain(file, Map.of()));
		// Measurements split them: tortoise converges towards its real slow speed while hare keeps the optimistic seed.
		scheduler.report("tortoise.example.com", MB, SECOND);
		assertEquals("hare.example.com", scheduler.chooseDomain(file, Map.of()));
	}

	@Test
	void ignoresAbsurdSamplesInsteadOfRecordingInfiniteSpeed() {
		DownloadScheduler scheduler = new DownloadScheduler();
		scheduler.report("a.example.com", 10 * MB, SECOND);
		scheduler.report("b.example.com", 20 * MB, SECOND);
		scheduler.report("c.example.com", MB, 0);
		// If the zero-duration sample were recorded as infinite speed, c would win despite being listed last.
		QueuedFile<String> file = new QueuedFile<>("file", 100 * MB, List.of("a.example.com", "b.example.com", "c.example.com"));
		assertEquals("b.example.com", scheduler.chooseDomain(file, Map.of()));
	}

	@Test
	void returnsNullForAFileWithoutCandidateDomains() {
		DownloadScheduler scheduler = new DownloadScheduler();
		assertNull(scheduler.chooseDomain(new QueuedFile<>("file", MB, List.of()), Map.of()));
	}

	@Test
	void theFirstMeasurementIsTheEstimateSoARatioIsNeverSeedDecided() {
		DownloadScheduler scheduler = new DownloadScheduler();
		scheduler.report("fast.example.com", 10 * MB, SECOND);
		scheduler.report("slow.example.com", DownloadScheduler.MIN_SAMPLE_BYTES, SECOND);
		assertEquals(10.0 * MB, scheduler.measuredSpeed("fast.example.com"));
		assertEquals((double) DownloadScheduler.MIN_SAMPLE_BYTES, scheduler.measuredSpeed("slow.example.com"));
		QueuedFile<String> file = new QueuedFile<>("file", 10 * MB, List.of("fast.example.com", "slow.example.com"));
		assertEquals("fast.example.com", scheduler.chooseDomain(file, Map.of()));
	}

	@Test
	void blendsLaterSamplesIntoTheEstimate() {
		DownloadScheduler scheduler = new DownloadScheduler();
		scheduler.report("a.example.com", 10 * MB, SECOND);
		assertEquals(10.0 * MB, scheduler.measuredSpeed("a.example.com"));
		scheduler.report("a.example.com", MB, SECOND);
		Double blended = scheduler.measuredSpeed("a.example.com");
		assertEquals(5.5 * MB, blended, "the half-factor blend of the sample over the previous estimate");
		scheduler.report("a.example.com", MB, SECOND);
		assertEquals(3.25 * MB, scheduler.measuredSpeed("a.example.com"), "repeated samples converge the estimate onto reality");
	}

	@Test
	void accumulatesSmallWindowsUntilTheyCrossTheFloor() {
		DownloadScheduler scheduler = new DownloadScheduler();
		assertFalse(scheduler.report("a.example.com", 10 * KB, SECOND));
		assertNull(scheduler.measuredSpeed("a.example.com"));
		assertFalse(scheduler.report("a.example.com", 15 * KB, SECOND));
		assertNull(scheduler.measuredSpeed("a.example.com"));
		assertTrue(scheduler.report("a.example.com", 20 * KB, SECOND));
		assertEquals(15.0 * KB, scheduler.measuredSpeed("a.example.com"));
	}

	@Test
	void aStaleMeasurementCountsAsUnmeasuredEverywhere() {
		AtomicLong nanos = new AtomicLong();
		DownloadScheduler scheduler = new DownloadScheduler(nanos::get);
		scheduler.report("a.example.com", 10 * MB, SECOND);
		nanos.set(DownloadScheduler.SAMPLE_STALE_NANOS);
		assertNotNull(scheduler.measuredSpeed("a.example.com"), "exactly at the stale threshold the measurement is still fresh");
		nanos.set(DownloadScheduler.SAMPLE_STALE_NANOS + 1);
		assertNull(scheduler.measuredSpeed("a.example.com"), "one nanosecond past the threshold the measurement is unmeasured");
		// Unmeasured everywhere: the fossil estimate must not win the choice against an unknown domain's optimistic seed.
		QueuedFile<String> file = new QueuedFile<>("file", MB, List.of("b.example.com", "a.example.com"));
		assertEquals("b.example.com", scheduler.chooseDomain(file, Map.of()));
	}

	@Test
	void aSampleAfterAGapReplacesTheFossilInsteadOfBlendingIt() {
		AtomicLong nanos = new AtomicLong();
		DownloadScheduler scheduler = new DownloadScheduler(nanos::get);
		scheduler.report("a.example.com", 16 * MB, SECOND);
		assertEquals(16.0 * MB, scheduler.measuredSpeed("a.example.com"));
		// A fresh sample past the stale threshold stands on its own. Blending the 16 MiB fossil in would land halfway at
		// 10 MiB, four times the truth, so this fails the moment the previous stamp is not read before it is overwritten.
		nanos.set(2 * DownloadScheduler.SAMPLE_STALE_NANOS);
		scheduler.report("a.example.com", 4 * MB, SECOND);
		assertEquals(4.0 * MB, scheduler.measuredSpeed("a.example.com"));
	}

	@Test
	void aNeverMeasuredDomainHasNoMeasuredSpeed() {
		DownloadScheduler scheduler = new DownloadScheduler();
		assertNull(scheduler.measuredSpeed("never.example.com"));
	}
}
