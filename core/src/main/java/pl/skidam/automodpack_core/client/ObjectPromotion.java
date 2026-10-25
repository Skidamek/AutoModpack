package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The CAS side of a finished transfer, identical for the host wire and a platform source: turn the stored slices into one
 * private file, hash it, and swap it into the object store. Nothing here knows where the bytes came from.
 */
final class ObjectPromotion {

	private final DataRootResolver.Layout dataLayout;
	private final ObjectBatch batch;

	ObjectPromotion(DataRootResolver.Layout dataLayout) {
		this(dataLayout, null);
	}

	/** With a batch, verified objects are held for its one sync instead of each being forced into the store on its own. */
	ObjectPromotion(DataRootResolver.Layout dataLayout, ObjectBatch batch) {
		this.dataLayout = dataLayout;
		this.batch = batch;
	}

	/** Creates the task's slice directory {@code staging/<sha1>/}; null with the failure recorded means the attempt cannot start. */
	Path preparePartial(String sha1, QueuedDownload task) {
		try {
			if (task.partialFile == null) {
				task.partialFile = PartialResume.directory(dataLayout.stagingDirectory(), sha1);
				Files.createDirectories(task.partialFile);
			}
			return task.partialFile;
		} catch (IOException e) {
			task.lastFailureCategory = DownloadManager.FailureCategory.LOCAL_STORAGE;
			LOGGER.warn("Failed to create temporary CAS object {}", sha1, e);
			return null;
		}
	}

	/**
	 * Verifies the finished slices and swaps them into the CAS store; false with the failure recorded means the download is
	 * retried. A one-tile object is moved to a private name and published as is, a larger one is hashed while it is stitched,
	 * so either way the bytes are written once more at most and read exactly once before they are published.
	 */
	boolean promote(String sha1, QueuedDownload task, Path partial, FileCache cache) {
		Path assembled = batch != null ? PartialResume.newHeldFile(partial) : dataLayout.stagingDirectory().resolve("." + sha1 + "." + UUID.randomUUID() + ".obj");
		boolean held = false;
		try {
			String hashed;
			if (PartialResume.adoptSoleTile(partial, assembled, task.fileSize)) {
				hashed = HashUtils.hashFile(assembled);
			} else {
				hashed = PartialResume.assembleHashing(partial, assembled, task.fileSize);
			}
			if (batch != null) {
				VerifiedFileTransfer.requireHashed(assembled, task.fileSize, sha1, hashed);
				PartialResume.deleteTiles(partial, task.fileSize);
				batch.hold(new ObjectBatch.Held(sha1, task.fileSize, assembled, partial, task));
				held = true;
				// The batch owns the slice directory from here: it deletes it once the object is durable in the store.
				task.partialFile = null;
			} else {
				VerifiedFileTransfer.promoteHashed(assembled, dataLayout.objectFile(sha1), task.fileSize, sha1, hashed, cache);
			}
		} catch (VerifiedFileTransfer.VerificationMismatchException e) {
			task.lastFailureCategory = DownloadManager.FailureCategory.REMOTE_SOURCE;
			LOGGER.warn("Size or hash mismatch for downloaded file {}", task.file.getFileName());
			deletePartial(task);
			return false;
		} catch (IOException e) {
			task.lastFailureCategory = DownloadManager.FailureCategory.LOCAL_STORAGE;
			LOGGER.warn("Failed to persist verified CAS object {}", task.file.getFileName(), e);
			deletePartial(task);
			return false;
		} finally {
			if (!held) try {
				ImmutableFiles.deleteIfExists(assembled);
			} catch (IOException ignored) {
			}
		}
		deletePartial(task);
		task.lastFailureCategory = DownloadManager.FailureCategory.UNKNOWN;
		return true;
	}

	/**
	 * Publishes an object an earlier run verified and held but may have lost to a power cut before its sync. The held bytes
	 * are untrusted, so they are hashed again and forced like any download; true means the store now has the object.
	 */
	boolean adoptHeld(String sha1, QueuedDownload task, FileCache cache) {
		Path directory = PartialResume.directory(dataLayout.stagingDirectory(), sha1);
		try {
			for (Path heldFile : PartialResume.heldFiles(directory)) {
				if (batch != null && batch.holds(heldFile)) continue;
				Path adopted = dataLayout.stagingDirectory().resolve("." + sha1 + "." + UUID.randomUUID() + ".obj");
				try {
					Files.move(heldFile, adopted);
					VerifiedFileTransfer.promoteHashed(adopted, dataLayout.objectFile(sha1), task.fileSize, sha1, HashUtils.hashFile(adopted), cache);
					PartialResume.delete(directory);
					return true;
				} catch (VerifiedFileTransfer.VerificationMismatchException torn) {
					LOGGER.info("Dropping a held copy of {} that did not survive to its sync", task.file.getFileName());
				} finally {
					ImmutableFiles.deleteIfExists(adopted);
					ImmutableFiles.deleteIfExists(heldFile);
				}
			}
		} catch (IOException e) {
			LOGGER.debug("Could not adopt a held copy of {}; downloading it again", task.file.getFileName(), e);
		}
		return false;
	}

	/** Deletes the task's slice directory; the next attempt, if any, starts from zero. */
	static void deletePartial(QueuedDownload task) {
		if (task.partialFile == null) return;
		PartialResume.delete(task.partialFile);
		task.partialFile = null;
	}
}
