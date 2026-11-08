package pl.skidam.automodpack_core.client;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import pl.skidam.automodpack_core.loader.FileInspection;
import pl.skidam.automodpack_core.utils.DownloadSource;

/** One file the queue is holding: what it is, where it can come from, and what the attempts so far cost it. */
public class QueuedDownload {

	/** The queue key this task lives under; the dispatch queue cannot find the task without it. */
	public final FileInspection.HashPathPair key;
	public final Path file;
	public final List<DownloadSource> sources;
	/** Every source's host, parsed once at enqueue, so a source URL's userinfo never becomes a map key or a log line. */
	public final List<String> domains;
	public final String murmur;
	public final String fileType;
	public final long fileSize;
	public final int seq;
	public final Map<String, Integer> domainFailures = new HashMap<>();
	public int attempts;
	public final Runnable successCallback;
	public final Consumer<DownloadManager.FailureCategory> failureCallback;
	/** UNKNOWN until something judges this attempt; a null category would make "undecided" and "just succeeded" one value. */
	public DownloadManager.FailureCategory lastFailureCategory = DownloadManager.FailureCategory.UNKNOWN;
	public boolean needsMetadataRefetch;
	/** The one partial temp of the whole task: kept across failed attempts as the resume prefix, deleted at its ends. */
	public Path partialFile;

	public QueuedDownload(FileInspection.HashPathPair key, Path file, List<DownloadSource> sources, String murmur, String fileType, long fileSize, int seq, Runnable successCallback,
			Consumer<DownloadManager.FailureCategory> failureCallback) {
		this.key = key;
		this.file = file;
		this.sources = new ArrayList<>(sources);
		this.murmur = murmur;
		this.fileType = fileType;
		this.fileSize = fileSize;
		this.seq = seq;
		this.successCallback = successCallback;
		this.failureCallback = failureCallback;
		this.domains = new ArrayList<>(sources.size());
		for (DownloadSource source : sources) domains.add(hostOf(source.url()));
	}

	/** The host a source URL names, or the whole URL when it names none - two such sources stay two domains instead of one silent bucket. */
	public static String hostOf(String url) {
		try {
			String host = URI.create(url).getHost();
			return host != null ? host : url;
		} catch (IllegalArgumentException unparseable) {
			return url;
		}
	}

	/** Whether the host wire can still serve this task; the caller still owns the transport check. */
	boolean hostRouteAvailable() {
		if (domainFailures.getOrDefault(RescuePolicy.HOST_DOMAIN, 0) >= DownloadManager.MAX_DOWNLOAD_ATTEMPTS) return false;
		// A task with no explicit host source falls back to the host wire once every platform domain has burned its budget.
		return sources.isEmpty() || domains.contains(RescuePolicy.HOST_DOMAIN) || !platformRouteAvailable();
	}

	/** Whether any platform source still has attempt budget. */
	boolean platformRouteAvailable() {
		for (String domain : domains) if (!RescuePolicy.HOST_DOMAIN.equals(domain) && domainFailures.getOrDefault(domain, 0) < DownloadManager.MAX_DOWNLOAD_ATTEMPTS) return true;
		return false;
	}
}
