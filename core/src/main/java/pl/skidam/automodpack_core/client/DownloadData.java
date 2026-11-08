package pl.skidam.automodpack_core.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** One dispatched attempt: the route it took, the domain that route is, and what it still owes. Read by the progress hooks on worker and lane threads. */
public class DownloadData {

	/** Where an attempt takes its bytes: a platform source occupies a worker, the host wire occupies none. */
	public enum Route {
		PLATFORM, HOST
	}

	/** What the player sees this object as; the bytes are addressed by {@link #sha1}, not by this. */
	public final String name;
	/** The object's hash, so a tile of it can be addressed without the task that queued it. */
	public final String sha1;
	public final String activeDomain;
	/** The object's full size, so a tile anywhere in it can be addressed. */
	public final long fileSize;
	/** This file's platform sources, so a probe waits for every candidate rather than the booked domain alone. */
	public final List<String> platformDomains;
	public final AtomicLong remainingBytes;
	/** PLATFORM occupies a worker for the whole attempt, HOST queues on the transport's lanes; the burned-budget fallback flips it mid-attempt. */
	public volatile Route route;
	/** The in-flight platform attempt's cancel handle, set by the worker right before the attempt starts and cleared after it; the rescue cancels through it. */
	public volatile HttpFileDownloader.AttemptControl attemptControl;

	DownloadData(String name, String sha1, String activeDomain, long fileSize) {
		this(name, sha1, activeDomain, fileSize, RescuePolicy.HOST_DOMAIN.equals(activeDomain) ? List.of() : List.of(activeDomain));
	}

	DownloadData(String name, String sha1, String activeDomain, long fileSize, List<String> platformDomains) {
		this.name = name;
		this.sha1 = sha1;
		this.activeDomain = activeDomain;
		this.fileSize = fileSize;
		this.platformDomains = List.copyOf(platformDomains);
		this.remainingBytes = new AtomicLong(fileSize);
	}

	/**
	 * The domain this attempt's bytes and backlog belong to. Dispatch picks a domain and fixes it here, but the burned-budget
	 * fallback can move a started attempt to the host wire, and everything the scheduler reads has to follow the bytes: an
	 * attempt booked under the platform it stopped using inflates the platform's estimate and starves its own rescue.
	 */
	public String bookedDomain() {
		return route == Route.HOST ? RescuePolicy.HOST_DOMAIN : activeDomain;
	}
}
