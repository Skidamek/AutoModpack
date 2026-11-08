package pl.skidam.automodpack_core.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * Verified downloads waiting for one batched sync before they enter the object store, the way git's batch fsync mode
 * keeps loose objects out of the real store until one flush covers them all. A held object lives in its slice directory
 * under a held name, so nothing that reads the store - the CAS-hit check, projection, the cache records - can see bytes
 * that a power cut could still tear. {@link #flush} forces every held file, renames each into the store, forces each
 * touched store directory once, and only then writes the cache records. A held file a crash leaves behind is untrusted
 * and is hashed again before it is used.
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

	private final Probe probe;
	private final DataRootResolver.Layout dataLayout;
	private final List<Held> held = new ArrayList<>();
	private final Set<Path> heldFiles = new HashSet<>();

	ObjectBatch(DataRootResolver.Layout dataLayout) {
		this(dataLayout, (step, path) -> {});
	}

	ObjectBatch(DataRootResolver.Layout dataLayout, Probe probe) {
		this.dataLayout = dataLayout;
		this.probe = probe;
	}

	synchronized void hold(Held object) {
		held.add(object);
		heldFiles.add(object.file());
	}

	synchronized int size() {
		return held.size();
	}

	/** Whether this run still owns {@code file}; adoption leaves those alone so the batch never loses an object it holds. */
	synchronized boolean holds(Path file) {
		return heldFiles.contains(file);
	}

	/**
	 * Publishes everything held so far and returns the tasks whose object could not be made durable in the store. Their
	 * held bytes are dropped with them, so a failed task never leaves a verified-looking object behind.
	 */
	List<QueuedDownload> flush() {
		List<Held> batch;
		synchronized (this) {
			if (held.isEmpty()) return List.of();
			batch = new ArrayList<>(held);
			held.clear();
			heldFiles.clear();
		}
		// Probe the directory that actually holds the objects, not the staging root's parent, so a staging mount keeps
		// its own enforcement verdict.
		boolean enforcementWorks = ImmutableFiles.enforcementWorks(batch.get(0).file());
		List<QueuedDownload> failed = new ArrayList<>();
		List<Held> forced = new ArrayList<>(batch.size());
		for (Held object : batch) {
			try {
				probe.reached(Step.BEFORE_FORCE, object.file());
				ImmutableFiles.protectAndForce(object.file(), enforcementWorks);
				probe.reached(Step.FORCED, object.file());
				forced.add(object);
			} catch (IOException e) {
				fail(object, failed, e);
			}
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
		for (Held object : batch) discard(object);
		return failed;
	}

	/**
	 * A held object whose own publication failed is still a success when the store already has its verified bytes, which
	 * is what another instance adopting or downloading the same object leaves behind. The check hashes, so a torn object
	 * never passes it.
	 */
	private void fail(Held object, List<QueuedDownload> failed, IOException cause) {
		Path target = dataLayout.objectFile(object.sha1());
		if (FileIntegrity.matches(target, object.size(), object.sha1())) {
			LOGGER.debug("Verified CAS object {} was published by another download", object.sha1(), cause);
			return;
		}
		if (!Files.exists(object.file(), LinkOption.NOFOLLOW_LINKS)) {
			// The staged bytes were removed under the run: no storage condition failed and the next acquisition can fetch them again.
			LOGGER.warn("The staged copy of {} was removed before it could be published; it is downloaded again", object.task().file.getFileName());
			object.task().lastFailureCategory = DownloadManager.FailureCategory.UNKNOWN;
		} else {
			LOGGER.warn("Failed to persist verified CAS object {}", object.task().file.getFileName(), cause);
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
