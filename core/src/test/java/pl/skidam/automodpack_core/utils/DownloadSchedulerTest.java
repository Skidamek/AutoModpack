package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.utils.DownloadScheduler.QueuedFile;

class DownloadSchedulerTest {

	private static final long MB = 1024L * 1024L;
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
}
