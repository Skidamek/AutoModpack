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

	ObjectPromotion(DataRootResolver.Layout dataLayout) {
		this.dataLayout = dataLayout;
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
		Path assembled = dataLayout.stagingDirectory().resolve("." + sha1 + "." + UUID.randomUUID() + ".obj");
		try {
			String hashed;
			if (PartialResume.adoptSoleTile(partial, assembled, task.fileSize)) {
				hashed = HashUtils.hashFile(assembled);
			} else {
				hashed = PartialResume.assembleHashing(partial, assembled, task.fileSize);
			}
			VerifiedFileTransfer.promoteHashed(assembled, dataLayout.objectFile(sha1), task.fileSize, sha1, hashed, cache);
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
			try {
				ImmutableFiles.deleteIfExists(assembled);
			} catch (IOException ignored) {
			}
		}
		deletePartial(task);
		task.lastFailureCategory = DownloadManager.FailureCategory.UNKNOWN;
		return true;
	}

	/** Deletes the task's slice directory; the next attempt, if any, starts from zero. */
	static void deletePartial(QueuedDownload task) {
		if (task.partialFile == null) return;
		PartialResume.delete(task.partialFile);
		task.partialFile = null;
	}
}
