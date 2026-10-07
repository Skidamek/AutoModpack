package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The CAS side of a finished transfer, identical for the host wire and a platform source: concatenate the stored slices,
 * hash them, and swap the result into the object store. Nothing here knows where the bytes came from.
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

	/** Concatenates finished slices and hashes them into the CAS store; false with the failure recorded means the download is retried. */
	boolean promote(String sha1, QueuedDownload task, Path partial, FileCache cache) {
		Path assembled = null;
		try {
			assembled = Files.createTempFile(dataLayout.stagingDirectory(), "." + sha1 + ".", ".obj");
			PartialResume.assemble(partial, assembled, task.fileSize);
			VerifiedFileTransfer.promoteAtomic(assembled, dataLayout.objectFile(sha1), task.fileSize, sha1, cache);
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
			if (assembled != null) try {
				Files.deleteIfExists(assembled);
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
