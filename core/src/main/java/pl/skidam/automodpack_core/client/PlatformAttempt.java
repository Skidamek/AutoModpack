package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import pl.skidam.automodpack_core.loader.FileInspection;
import pl.skidam.automodpack_core.platforms.FetchManager;
import pl.skidam.automodpack_core.protocol.LocalStorageException;
import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.protocol.StaleRangeException;
import pl.skidam.automodpack_core.protocol.WireTrace;
import pl.skidam.automodpack_core.utils.DownloadSource;

/**
 * One blocking platform pull: the HTTP status classification, the dead-link bookkeeping with its batched metadata
 * refetch, and the attempt's outcome. It records the result onto the task and books nothing itself, so the ladder that
 * owns the retry budget stays in one place.
 */
final class PlatformAttempt {

	private static final int HTTP_UNAUTHORIZED = 401;
	private static final int HTTP_NOT_FOUND = 404;
	private static final int HTTP_GONE = 410;

	private final HttpFileDownloader downloader;
	private final FetchManager metadataFetcher;

	PlatformAttempt(HttpFileDownloader downloader, FetchManager metadataFetcher) {
		this.downloader = Objects.requireNonNull(downloader, "downloader");
		this.metadataFetcher = Objects.requireNonNull(metadataFetcher, "metadataFetcher");
	}

	/** The source this task's platform domains can still serve, or null once its attempt budget is burned. */
	DownloadSource sourceFor(QueuedDownload task, String domain) {
		int sourceIndex = sourceIndexForDomain(task, domain);
		if (sourceIndex < 0) return null;
		boolean budgetLeft = task.attempts < DownloadManager.MAX_DOWNLOAD_ATTEMPTS * task.sources.size();
		return budgetLeft ? task.sources.get(sourceIndex) : null;
	}

	/** The whole-file HTTP pull behind the stored partial. The host transport is not involved. */
	DownloadManager.AttemptOutcome run(FileInspection.HashPathPair hashPathPair, QueuedDownload task, DownloadData data, DownloadSource source, Path partial, TransferSampler sampler)
			throws IOException, InterruptedException {
		data.attemptControl = new HttpFileDownloader.AttemptControl();
		try {
			// A request stops at a tile the other side already filled, so one attempt can be several requests. The route and
			// the attempt's budget are decided once, before the first one; only the bytes keep moving.
			while (true) {
				long offset = PartialResume.nextByte(partial, task.fileSize);
				if (offset >= task.fileSize || PartialResume.complete(partial, task.fileSize)) return DownloadManager.AttemptOutcome.COMPLETED;
				downloader.download(source, partial, task.fileSize, offset, sampler.progressHook(), sampler.rateFuseFloor(), data.attemptControl);
			}
		} catch (HttpFileDownloader.ReroutedAttemptException rerouted) {
			// A slow domain is not a broken one: this cancel was the rescue's own verdict, charged to nobody.
			WireTrace.log("RESCUE_CANCEL", "task", hashPathPair.hash(), "domain", data.activeDomain);
			return DownloadManager.AttemptOutcome.REROUTED;
		} catch (LocalStorageException e) {
			task.lastFailureCategory = DownloadManager.FailureCategory.LOCAL_STORAGE;
			LOGGER.warn("Failed to write temporary CAS object {}", hashPathPair.hash(), e);
			ObjectPromotion.deletePartial(task);
			return DownloadManager.AttemptOutcome.FAILED;
		} catch (HttpFileDownloader.HttpStatusException e) {
			task.lastFailureCategory = DownloadManager.FailureCategory.REMOTE_SOURCE;
			if (isDeadLink(e.statusCode())) markDeadLink(hashPathPair.hash(), task);
			else if (source.provider() == DownloadSource.Provider.CURSEFORGE && e.statusCode() == HTTP_UNAUTHORIZED) {
				LOGGER.warn("CurseForge rejected the download API key with HTTP 401; trying the next source");
				task.domainFailures.merge(data.activeDomain, DownloadManager.MAX_DOWNLOAD_ATTEMPTS, Integer::sum);
			}
			return DownloadManager.AttemptOutcome.FAILED;
		} catch (StaleRangeException e) {
			// The partial cannot serve as the resume prefix: worthless, so the next attempt starts clean.
			task.lastFailureCategory = DownloadManager.FailureCategory.REMOTE_SOURCE;
			LOGGER.warn("Stored partial for CAS object {} is stale for resume; restarting from zero", hashPathPair.hash());
			ObjectPromotion.deletePartial(task);
			return DownloadManager.AttemptOutcome.FAILED;
		} catch (IOException e) {
			task.lastFailureCategory = DownloadManager.FailureCategory.REMOTE_SOURCE;
			LOGGER.debug("Remote source failed for CAS object {}", hashPathPair.hash(), e);
			return DownloadManager.AttemptOutcome.FAILED;
		} finally {
			data.attemptControl = null;
			// Every byte that arrived is real bandwidth data for the path it actually travelled, whatever happened to the transfer.
			sampler.flush();
		}
	}

	private int sourceIndexForDomain(QueuedDownload task, String domain) {
		for (int i = 0; i < task.domains.size(); i++) {
			if (task.domains.get(i).equals(domain)) return i;
		}
		return -1;
	}

	private static boolean isDeadLink(int statusCode) {
		return statusCode == HTTP_NOT_FOUND || statusCode == HTTP_GONE;
	}

	private void markDeadLink(String sha1, QueuedDownload task) {
		if (!metadataFetcher.markDeadPlatformLink(sha1, task.murmur, task.fileType)) return;
		task.needsMetadataRefetch = true;
		LOGGER.warn("Dead platform link for CAS object {}; its metadata will be refetched before the next attempt", sha1);
	}

	/**
	 * Swaps in the refetched metadata of a dead link. The batched calls run on the transport's own executor and this
	 * worker joins them, so a platform worker does wait on the network here - a task parked in the refetch holds no
	 * cancel handle yet, which is why the rescue re-routes such a task by re-reading its state instead.
	 */
	void refreshDeadLinkSources(String sha1, QueuedDownload task) {
		if (!task.needsMetadataRefetch) return;
		task.needsMetadataRefetch = false;
		List<DownloadSource> fresh = metadataFetcher.awaitMetadataRefetch(sha1);
		if (fresh.isEmpty()) return;
		task.sources.clear();
		task.sources.addAll(fresh);
		task.domains.clear();
		for (DownloadSource source : fresh) task.domains.add(QueuedDownload.hostOf(source.url()));
		task.attempts = 0;
		task.domainFailures.clear();
	}
}
