package pl.skidam.automodpack_core.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.ForceProfile;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * Verified downloads remain private until all content and namespace barriers have completed. One daemon pre-forces
 * held files of at most 1 MiB while downloads continue, starting lazily on the first eligible object; producers append
 * references and never wait for force IO or queue capacity. Larger or unknown-size files remain in the held ledger and use
 * the join-time force. A store whose probe force is slow keeps every object on the join-time path, because interleaving
 * fsyncs with download writes costs more on a spinning disk than the overlap saves; that probe runs on the first eligible
 * object of the run, never on the thread that builds the download batch.
 * {@link #flush} closes admission, waits for every admitted force, forces any remainder, and only then publishes, forces
 * touched store directories, and writes cache records. A force that failed fails only its own object; the rest publish.
 * Closing alone drains admitted forces but never publishes or interrupts native IO. Held files surviving a crash are
 * untrusted and must be rehashed before adoption.
 */
final class ObjectBatch {
	private static final Logger LOGGER = LogManager.getLogger(ObjectBatch.class);

	record Held(String sha1, long size, Path file, Path sliceDirectory, QueuedDownload task) {}

	enum Step {
		BEFORE_FORCE, FORCED, BEFORE_PUBLISH, PUBLISHED, DIRECTORY_FORCED, BEFORE_RECORD, RECORDED
	}

	@FunctionalInterface
	interface Probe {
		void reached(Step step, Path path);
	}

	private static final long MAX_ASYNC_FORCE_BYTES = 1L << 20;

	private final Probe probe;
	private final DataRootResolver.Layout dataLayout;
	private final OverlappedForcer forcer;
	private final boolean asynchronous;
	// Guards the held ledger, the closed flag, and pump submission, so closing admission cannot race a producer's hold.
	private final Object admission = new Object();
	private final Object overlapProbe = new Object();
	private final ArrayDeque<OverlappedForcer.Work> held = new ArrayDeque<>();
	/** References only: bytes stay private in the held file until publication. */
	private final Set<Path> owned = ConcurrentHashMap.newKeySet();
	// Serializes whole flushes, so only one publisher owns the cache file and one drain wins the ledger.
	private final Object flushLock = new Object();
	// Accessed only by one force thread at a time: the pump, then the joining publisher. All held files share the store.
	private Boolean enforcementWorks;
	private Boolean overlapForces;
	private boolean closed;

	static ObjectBatch asynchronous(DataRootResolver.Layout layout) {
		return asynchronous(layout, (step, path) -> {});
	}

	static ObjectBatch asynchronous(DataRootResolver.Layout layout, Probe probe) {
		Objects.requireNonNull(layout, "layout");
		Objects.requireNonNull(probe, "probe");
		return new ObjectBatch(layout, probe, true);
	}

	/** An overlapping batch whose force a test can fail without a real media fault. */
	static ObjectBatch asynchronous(DataRootResolver.Layout layout, Probe probe, OverlappedForcer.Forcer forcer) {
		Objects.requireNonNull(layout, "layout");
		Objects.requireNonNull(probe, "probe");
		Objects.requireNonNull(forcer, "forcer");
		return new ObjectBatch(layout, probe, forcer);
	}

	ObjectBatch(DataRootResolver.Layout layout) {
		this(layout, (step, path) -> {});
	}

	ObjectBatch(DataRootResolver.Layout layout, Probe probe) {
		Objects.requireNonNull(layout, "layout");
		Objects.requireNonNull(probe, "probe");
		this.dataLayout = layout;
		this.probe = probe;
		this.asynchronous = false;
		this.forcer = new OverlappedForcer(this::forceHeld);
	}

	private ObjectBatch(DataRootResolver.Layout layout, Probe probe, boolean asynchronous) {
		this.dataLayout = layout;
		this.probe = probe;
		this.asynchronous = asynchronous;
		this.forcer = new OverlappedForcer(this::forceHeld);
	}

	private ObjectBatch(DataRootResolver.Layout layout, Probe probe, OverlappedForcer.Forcer forcer) {
		this.dataLayout = layout;
		this.probe = probe;
		this.asynchronous = true;
		this.forcer = new OverlappedForcer(forcer);
	}

	/** No producer waits for force IO or queue capacity; the ledger and pump submission share one admission lock. */
	void hold(Held object) {
		OverlappedForcer.Work work = new OverlappedForcer.Work(object);
		boolean overlap = overlaps(object.size());
		synchronized (admission) {
			owned.add(object.file());
			held.add(work);
			if (!closed && overlap) forcer.submit(work);
		}
	}

	/** The size gate first, so a large or unknown-size object never pays for the store probe. */
	private boolean overlaps(long size) {
		if (!asynchronous || size < 0 || size > MAX_ASYNC_FORCE_BYTES) return false;
		synchronized (overlapProbe) {
			if (overlapForces == null) {
				overlapForces = ForceProfile.allowsOverlappedForce(dataLayout.stagingDirectory());
				if (!overlapForces) LOGGER.info("This store is not proven solid-state; held objects are forced at join");
			}
			return overlapForces;
		}
	}

	int size() {
		synchronized (admission) {
			return held.size();
		}
	}

	boolean holds(Path file) {
		return owned.contains(file);
	}

	/** Closes async admission without interrupting native force IO; the pump still attempts everything it already accepted. */
	void close() {
		synchronized (admission) {
			closed = true;
		}
		forcer.closeAdmission();
	}

	/**
	 * Forces every remaining file before publishing any, then persists namespace barriers and cache records. Like the
	 * original synchronous flush, an interrupt alone does not abandon an already verified batch: waiting for the pump
	 * finishes uninterruptibly and the caller's interrupt flag is restored on return. A force failure is final for its own
	 * object and only its task fails; a failure before reaching the force is retried once here with a fresh descriptor.
	 */
	List<QueuedDownload> flush() {
		synchronized (flushLock) {
			List<OverlappedForcer.Work> pending;
			synchronized (admission) {
				closed = true;
				pending = new ArrayList<>(held);
				held.clear();
			}
			forcer.closeAdmission();
			boolean interrupted = forcer.awaitIdle();
			try {
				try {
					return pending.isEmpty() ? List.of() : publish(pending);
				} finally {
					for (OverlappedForcer.Work next : pending) owned.remove(next.object.file());
				}
			} finally {
				if (interrupted) Thread.currentThread().interrupt();
			}
		}
	}

	/** The batch's own force: one read-only enforcement verdict per store, then the seal-and-force itself. */
	private void forceHeld(Path file) throws IOException {
		probe.reached(Step.BEFORE_FORCE, file);
		if (enforcementWorks == null) enforcementWorks = ImmutableFiles.enforcementWorks(file);
		ImmutableFiles.protectAndForce(file, enforcementWorks);
		probe.reached(Step.FORCED, file);
	}

	/**
	 * The never-overlapped object and the retry of a failure that never reached the force share this one path; a reported
	 * force failure is final and never comes here. A retry first clears the read-only policy its failed attempt may
	 * already have applied. Only IO fails its object here: anything else on the caller's own thread is a bug, not a
	 * storage condition, and escapes the flush.
	 */
	private void forceAtJoin(OverlappedForcer.Work work) {
		if (work.failure != null) try {
			ImmutableFiles.unprotect(work.object.file());
		} catch (IOException failure) {
			work.failure = failure;
			return;
		}
		work.failure = null;
		try {
			forcer.force(work);
			work.forced = true;
		} catch (IOException failure) {
			work.failure = failure;
		}
	}

	/** A force or close that reported failure cannot be proven durable by a second attempt, not even one with a fresh descriptor. */
	private static boolean terminal(Throwable failure) {
		if (failure == null) return false;
		if (failure instanceof ImmutableFiles.ForceFailedException) return true;
		// A close barrier error can be suppressed behind a preceding protection failure.
		for (Throwable suppressed : failure.getSuppressed()) if (suppressed instanceof ImmutableFiles.ForceFailedException) return true;
		return false;
	}

	private List<QueuedDownload> publish(List<OverlappedForcer.Work> pending) {
		List<QueuedDownload> failed = new ArrayList<>();
		List<Held> forced = new ArrayList<>(pending.size());
		for (OverlappedForcer.Work next : pending) {
			if (!next.forced && !terminal(next.failure)) forceAtJoin(next);
			if (next.forced) forced.add(next.object);
			else fail(next.object, failed, next.failure);
		}

		Map<Path, List<Held>> byDirectory = new LinkedHashMap<>();
		for (Held object : forced) {
			try {
				probe.reached(Step.BEFORE_PUBLISH, object.file());
				Path parent = VerifiedFileTransfer.publishSealed(object.file(), dataLayout.objectFile(object.sha1()), object.size(), object.sha1());
				probe.reached(Step.PUBLISHED, dataLayout.objectFile(object.sha1()));
				byDirectory.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(object);
			} catch (IOException e) {
				fail(object, failed, e);
			}
		}
		List<Held> durable = new ArrayList<>(forced.size());
		for (Map.Entry<Path, List<Held>> directory : byDirectory.entrySet()) {
			FileTrees.forceDirectory(directory.getKey());
			probe.reached(Step.DIRECTORY_FORCED, directory.getKey());
			durable.addAll(directory.getValue());
		}
		if (!durable.isEmpty()) {
			Map<Path, String> records = new LinkedHashMap<>(durable.size());
			for (Held object : durable) {
				Path target = dataLayout.objectFile(object.sha1());
				probe.reached(Step.BEFORE_RECORD, target);
				records.put(target, object.sha1());
			}
			try (FileCache cache = FileCache.open(dataLayout.fileCacheDirectory())) {
				try {
					cache.overwriteCacheBatch(records);
					for (Held object : durable) probe.reached(Step.RECORDED, dataLayout.objectFile(object.sha1()));
				} catch (IOException batchFailure) {
					// One bad stat must not hide the rest: fall back to the per-object path that isolates it.
					for (Held object : durable) {
						try {
							cache.overwriteCache(dataLayout.objectFile(object.sha1()), object.sha1());
							probe.reached(Step.RECORDED, dataLayout.objectFile(object.sha1()));
						} catch (IOException e) {
							fail(object, failed, e);
						}
					}
				}
			} catch (IOException e) {
				for (Held object : durable) if (!failed.contains(object.task())) fail(object, failed, e);
			}
		}
		for (OverlappedForcer.Work next : pending) discard(next.object);
		return failed;
	}

	/**
	 * A held object whose own publication failed is still a success when the store already has its verified bytes, which
	 * is what another instance adopting or downloading the same object leaves behind. The check hashes, so a torn object
	 * never passes it.
	 */
	private void fail(Held object, List<QueuedDownload> failed, Throwable cause) {
		Path target = dataLayout.objectFile(object.sha1());
		if (FileIntegrity.matches(target, object.size(), object.sha1())) {
			LOGGER.debug("Verified CAS object {} was published by another download", object.sha1(), cause);
			return;
		}
		if (!Files.exists(object.file(), LinkOption.NOFOLLOW_LINKS)) {
			// The staged bytes were removed under the run: no storage condition failed and the next acquisition can fetch them again.
			LOGGER.warn("The staged copy of {} was removed before it could be published; it is downloaded again", object.task().name);
			object.task().lastFailureCategory = DownloadManager.FailureCategory.UNKNOWN;
		} else {
			LOGGER.warn("Failed to persist verified CAS object {}", object.task().name, cause);
			object.task().lastFailureCategory = DownloadManager.FailureCategory.LOCAL_STORAGE;
		}
		failed.add(object.task());
	}

	private static void discard(Held object) {
		try {
			ImmutableFiles.deleteIfExists(object.file());
		} catch (IOException ignored) {
		}
		PartialResume.delete(object.sliceDirectory());
	}
}
