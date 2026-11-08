package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.loader.FileInspection;
import pl.skidam.automodpack_core.utils.DownloadSource;

class DownloadQueueTest {

	private static QueuedDownload task(String hash, long size, String fileName, List<DownloadSource> sources) {
		return new QueuedDownload(new FileInspection.HashPathPair(hash, Path.of(fileName)), Path.of(fileName), sources, null, "mods", size, 0, () -> {}, category -> {});
	}

	@Test
	void hostOrderSkipsPlatformOnlyTasksAndDoesNotRedispatchStaleEntries() {
		DownloadQueue queue = new DownloadQueue();
		QueuedDownload platform = task("a".repeat(40), 10, "platform.jar", List.of(new DownloadSource("https://cdn.example/platform.jar", DownloadSource.Provider.MODRINTH)));
		QueuedDownload hostOnly = task("b".repeat(40), 5, "host.jar", List.of());
		queue.enqueue(platform);
		queue.enqueue(hostOnly);

		assertNull(queue.pollHostDispatchable(false));
		assertEquals(hostOnly.key, queue.pollHostDispatchable(true).key);
		assertNull(queue.pollHostDispatchable(true), "a platform-only task is never handed to the host order");
		assertEquals(platform.key, queue.pollDispatchable(task -> true).key, "the host-dispatched task's stale main-order entry is skipped");
		assertNull(queue.pollDispatchable(task -> true));
	}

	@Test
	void hostOrderHonoursTheHostAttemptBudget() {
		DownloadQueue queue = new DownloadQueue();
		QueuedDownload task = task("c".repeat(40), 5, "host.jar", List.of());
		task.domainFailures.put(RescuePolicy.HOST_DOMAIN, DownloadManager.MAX_DOWNLOAD_ATTEMPTS);
		queue.enqueue(task);

		assertNull(queue.pollHostDispatchable(true));
		assertNull(queue.pollDispatchable(candidate -> candidate.platformRouteAvailable()));
	}
}
