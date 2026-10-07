package pl.skidam.automodpack_core.protocol;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * What one endpoint's transfer session knows about itself: where it terminates, what its lanes actually achieved, and the
 * one verdict every lane of it shares. The lane pool and the object transfers both report into it, so the run summary and
 * the per-client verdicts survive both of them.
 */
final class TransportSession {

	private static final Logger LOGGER = LogManager.getLogger();

	final String endpointHost;
	// The run's honest totals: takes sent (retries included), takes that were retries, and bytes that arrived.
	final AtomicLong takesSubmitted = new AtomicLong();
	final AtomicLong takeRetries = new AtomicLong();
	final AtomicLong bytesDownloaded = new AtomicLong();
	// The receipt's lane count reports the run's real parallelism - the high-water mark of simultaneously open lanes - not
	// the pool's constant, so a run that only ever opened one lane does not receipt five.
	final AtomicInteger lanesHighWater = new AtomicInteger();
	// Set once a take's response head proves the peer ignores Range (a 200 past the requested slice): every whole-object
	// task on this client then takes the object open-ended instead of tiling. One client is one endpoint - all lanes
	// terminate on the same peer and redirects drop authority - so the capability verdict is per client, not per file.
	private final AtomicBoolean rangeIgnoredHost = new AtomicBoolean();

	TransportSession(String endpointHost) {
		this.endpointHost = endpointHost;
	}

	boolean rangeIgnoredHost() {
		return rangeIgnoredHost.get();
	}

	/** Marks the whole client degraded once, with the one loud line naming the host every lane of this client terminates on. */
	void markRangeIgnoredHost() {
		if (rangeIgnoredHost.compareAndSet(false, true))
			LOGGER.warn("Host {} ignores HTTP Range requests; every object on this client now downloads in one open-ended take", endpointHost);
	}
}
