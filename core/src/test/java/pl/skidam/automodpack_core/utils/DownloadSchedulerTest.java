package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.utils.DownloadScheduler.Pick;
import pl.skidam.automodpack_core.utils.DownloadScheduler.QueuedFile;

class DownloadSchedulerTest {

	private static final long MB = 1024L * 1024L;
	private static final long SECOND = 1_000_000_000L;

	@Test
	void picksTheLargestFileFirstAndBreaksTiesInCallerOrder() {
		DownloadScheduler scheduler = new DownloadScheduler();
		List<QueuedFile<String>> queue = List.of(new QueuedFile<>("small", MB, List.of("a.example.com")), new QueuedFile<>("big", 50 * MB, List.of("a.example.com")),
				new QueuedFile<>("medium", 10 * MB, List.of("a.example.com")));
		assertEquals("big", scheduler.pick(queue, Map.of()).identity());
		List<QueuedFile<String>> tied = List.of(new QueuedFile<>("first", MB, List.of("a.example.com")), new QueuedFile<>("second", MB, List.of("a.example.com")));
		assertEquals("first", scheduler.pick(tied, Map.of()).identity());
	}

	@Test
	void fallsThroughAFileWithoutCandidatesInsteadOfIdlingTheSlot() {
		DownloadScheduler scheduler = new DownloadScheduler();
		List<QueuedFile<String>> queue = List.of(new QueuedFile<>("blocked", 50 * MB, List.of()), new QueuedFile<>("small", MB, List.of("a.example.com")));
		assertEquals("small", scheduler.pick(queue, Map.of()).identity());
		assertNull(scheduler.pick(List.of(new QueuedFile<>("blocked", MB, List.of())), Map.of()));
	}

	@Test
	void sendsTheBigFileToTheMeasuredFastSource() {
		DownloadScheduler scheduler = new DownloadScheduler();
		scheduler.report("fast.example.com", 10 * MB, SECOND);
		scheduler.report("slow.example.com", MB, SECOND);
		List<QueuedFile<String>> queue = List.of(new QueuedFile<>("big", 100 * MB, List.of("slow.example.com", "fast.example.com")), new QueuedFile<>("small", MB, List.of("slow.example.com", "fast.example.com")));
		Pick<String> pick = scheduler.pick(queue, Map.of());
		assertEquals("big", pick.identity());
		assertEquals("fast.example.com", pick.sourceDomain());
	}

	@Test
	void turnsAwayASourceOnceItsBacklogCatchesUp() {
		DownloadScheduler scheduler = new DownloadScheduler();
		scheduler.report("fast.example.com", 10 * MB, SECOND);
		scheduler.report("slow.example.com", MB, SECOND);
		List<QueuedFile<String>> queue = List.of(new QueuedFile<>("big", 10 * MB, List.of("fast.example.com", "slow.example.com")));
		assertEquals("fast.example.com", scheduler.pick(queue, Map.of()).sourceDomain());
		assertEquals("slow.example.com", scheduler.pick(queue, Map.of("fast.example.com", 500 * MB)).sourceDomain());
	}

	@Test
	void seedsUnknownSourcesOptimisticallyAndConvergesOnSamples() {
		DownloadScheduler scheduler = new DownloadScheduler();
		List<QueuedFile<String>> queue = List.of(new QueuedFile<>("file", 100 * MB, List.of("tortoise.example.com", "hare.example.com")));
		// Both unknown: equal optimistic seeds, so the caller's preference order decides.
		assertEquals("tortoise.example.com", scheduler.pick(queue, Map.of()).sourceDomain());
		// Measurements split them: tortoise converges towards its real slow speed while hare keeps the optimistic seed.
		scheduler.report("tortoise.example.com", MB, SECOND);
		assertEquals("hare.example.com", scheduler.pick(queue, Map.of()).sourceDomain());
	}

	@Test
	void ignoresAbsurdSamplesInsteadOfRecordingInfiniteSpeed() {
		DownloadScheduler scheduler = new DownloadScheduler();
		scheduler.report("a.example.com", 10 * MB, SECOND);
		scheduler.report("b.example.com", 20 * MB, SECOND);
		scheduler.report("c.example.com", MB, 0);
		// If the zero-duration sample were recorded as infinite speed, c would win despite being listed last.
		List<QueuedFile<String>> queue = List.of(new QueuedFile<>("file", 100 * MB, List.of("a.example.com", "b.example.com", "c.example.com")));
		assertEquals("b.example.com", scheduler.pick(queue, Map.of()).sourceDomain());
	}
}
