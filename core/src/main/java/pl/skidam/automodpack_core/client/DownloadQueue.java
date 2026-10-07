package pl.skidam.automodpack_core.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import pl.skidam.automodpack_core.client.DownloadData.Route;
import pl.skidam.automodpack_core.loader.FileInspection;

/**
 * The run's dispatch queue and its in-flight ledger, and nothing else: which queued file goes out next, and which files
 * are out. Both live here so the worker budget is one count over one map - a platform task occupies a worker for its whole
 * attempt, a host task for the moment it submits its take - instead of a hand-kept counter beside the data it counts.
 * Guarded by the manager's monitor, like the manager that pumps it.
 */
public final class DownloadQueue {

	private final Set<FileInspection.HashPathPair> queued = new HashSet<>();
	private final Map<FileInspection.HashPathPair, DownloadData> inFlight = new ConcurrentHashMap<>();
	// Largest file first, enqueue order breaks ties. A scan re-queues only the tasks ahead of the one it picked, so the
	// usual dispatch is one insert or one poll. Largest-first governs enqueue order only: past it the pool's waiter queue
	// is FIFO, so a tiny file can wait one pipeline generation behind big first-takes - no starvation, every settle
	// re-pumps the queue.
	private final PriorityQueue<QueuedDownload> dispatchOrder = new PriorityQueue<>(
			Comparator.comparingLong((QueuedDownload task) -> task.fileSize).reversed().thenComparingInt(task -> task.seq));

	public boolean queued(FileInspection.HashPathPair key) {
		return queued.contains(key);
	}

	public void enqueue(QueuedDownload task) {
		queued.add(task.key);
		dispatchOrder.add(task);
	}

	/** The largest queued file {@code dispatchable} accepts, or null when none of them can run now; the rest stay queued in order. */
	public QueuedDownload pollDispatchable(Predicate<QueuedDownload> dispatchable) {
		List<QueuedDownload> skipped = new ArrayList<>();
		QueuedDownload chosen = null;
		for (QueuedDownload candidate; (candidate = dispatchOrder.poll()) != null;) {
			if (dispatchable.test(candidate)) {
				chosen = candidate;
				break;
			}
			skipped.add(candidate);
		}
		dispatchOrder.addAll(skipped);
		if (chosen != null) queued.remove(chosen.key);
		return chosen;
	}

	public void start(FileInspection.HashPathPair key, DownloadData data) {
		inFlight.put(key, data);
	}

	public DownloadData finish(FileInspection.HashPathPair key) {
		return inFlight.remove(key);
	}

	/** How many in-flight tasks occupy a platform worker right now. */
	public int platformTasks() {
		int count = 0;
		for (DownloadData data : inFlight.values()) {
			if (data.route == Route.PLATFORM) count++;
		}
		return count;
	}

	public boolean isIdle() {
		return inFlight.isEmpty() && queued.isEmpty();
	}

	public int queuedCount() {
		return queued.size();
	}

	public int inFlightCount() {
		return inFlight.size();
	}

	public List<DownloadData> inFlightTasks() {
		return List.copyOf(inFlight.values());
	}

	public List<String> downloadingFileNames() {
		return inFlight.values().stream().map(DownloadData::getFileName).toList();
	}

	/** Bytes still owed by every in-flight task, per domain; the scheduler's backlog input. */
	public Map<String, Long> inFlightBacklog() {
		Map<String, Long> backlog = new HashMap<>();
		for (DownloadData data : inFlight.values()) backlog.merge(data.bookedDomain(), Math.max(0, data.remainingBytes.get()), Long::sum);
		return backlog;
	}

	/** Empties the queue without dispatching it; a cancelled run leaves nothing behind to pick up. */
	public void clearQueued() {
		queued.clear();
		dispatchOrder.clear();
	}
}
