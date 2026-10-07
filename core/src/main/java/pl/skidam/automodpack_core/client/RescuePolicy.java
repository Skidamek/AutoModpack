package pl.skidam.automodpack_core.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.utils.ByteFormat;
import pl.skidam.automodpack_core.utils.DownloadScheduler;

/**
 * The one policy that lets the attached host wire take traffic away from the platform CDNs: it takes over where the
 * fastest platform domain still measures this many times slower than the host - so one healthy candidate keeps the
 * platforms first - once both are measured, and never where a platform domain measures fast enough in its own right.
 * The engage test is a ratio, never a byte rate, so the client's link, the CDN's and the number of attempts sharing
 * them cancel out - the whole reason a per-domain byte floor cannot decide it (five platform attempts share one link,
 * so a floor either indicts every healthy CDN on a slow client or clears a broken one on a fast link). The adequacy
 * bar is the one absolute number here: it exists to say a platform that already delivers needs no fixing, which is
 * the distinction a ratio alone cannot make.
 */
public final class RescuePolicy {

	private static final Logger LOGGER = LogManager.getLogger();

	// Domain label for transfers served by the attached AutoModpack host client instead of a remote platform source.
	public static final String HOST_DOMAIN = "internal_client";

	// The engage ratio: the host takes a platform domain's traffic only once it measures this many times faster. What it
	// is: the owner's "a magnitude slower", one order of magnitude. What it is not: a measured band - the two cases
	// behind it measured 2.5x and ~170x, so nothing pins the edge between them. Every verdict logs the observed ratio,
	// so the constant can be re-derived from field reports instead of argued from one bench.
	private static final int ENGAGE_RATIO = 10;

	// The adequacy bar: a platform domain this fast needs no rescue, however much faster the host measures - there is no
	// problem to fix, only a faster path to take, and taking it costs the host's uplink and disk. What it is: the owner's
	// bar that a steady platform delivery of 10 MiB/s is broadband-class (a 1 GB file in ~102 s; real packs run 100 MB
	// to 4 GB). What it is not: a claim about any one player's link - a domain below the bar still has to lose the ratio
	// above to be rescued, and every verdict logs observed speeds so field reports can re-derive the bar like ENGAGE_RATIO.
	private static final long ADEQUATE_PLATFORM_SPEED = 10L * 1024 * 1024;

	private final DownloadScheduler scheduler;

	public RescuePolicy(DownloadScheduler scheduler) {
		this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
	}

	/**
	 * The route order for one queued file: the host wire ahead of the platform candidates while this policy engages, the
	 * candidates alone while it does not. An unmeasured host is never offered - it would take over on the scheduler's
	 * optimistic seed - and neither is any task whose candidates the host does not outrun, so one healthy candidate keeps
	 * the platforms first. One candidate measuring adequate is enough to keep them ahead at any ratio: an adequate source
	 * needs no rescue, and where every candidate measures inadequate the host must still outrun the fastest by
	 * ENGAGE_RATIO.
	 */
	public List<String> offer(List<String> platformDomains) {
		if (platformDomains.isEmpty()) return platformDomains;
		Double host = scheduler.measuredSpeed(HOST_DOMAIN);
		if (host == null) return platformDomains;
		double fastest = 0;
		for (String domain : platformDomains) {
			Double measured = scheduler.measuredSpeed(domain);
			if (measured == null) return platformDomains;
			fastest = Math.max(fastest, measured);
		}
		if (adequate(fastest)) {
			LOGGER.debug("a platform domain measures at least {}; staying platform-first", ByteFormat.formatSpeed(ADEQUATE_PLATFORM_SPEED));
			return platformDomains;
		}
		if (host < ENGAGE_RATIO * fastest) {
			LOGGER.debug("the host measured {}, {}x the fastest platform domain at {}; staying platform-first", ByteFormat.formatSpeed(host.longValue()),
					ratio(host, fastest), ByteFormat.formatSpeed((long) fastest));
			return platformDomains;
		}
		List<String> rerouted = new ArrayList<>(platformDomains.size() + 1);
		rerouted.add(HOST_DOMAIN);
		rerouted.addAll(platformDomains);
		return rerouted;
	}

	/**
	 * Whether the host should be measured for this file: every candidate platform already has a live sample, at least one
	 * is under the bar, and the host itself has none. One unmeasured candidate may still be the fast path, so no tile is
	 * taken until it has spoken.
	 */
	public boolean worthProbing(List<String> platformDomains) {
		if (scheduler.measuredSpeed(HOST_DOMAIN) != null) return false;
		if (platformDomains == null || platformDomains.isEmpty()) return false;
		boolean anyInadequate = false;
		for (String domain : platformDomains) {
			if (HOST_DOMAIN.equals(domain)) continue;
			Double measured = scheduler.measuredSpeed(domain);
			if (measured == null) return false;
			if (!adequate(measured)) anyInadequate = true;
		}
		return anyInadequate;
	}

	/** True while this platform domain is measurably slower than the host and under the adequacy bar: the state a queued task is routed on and the in-flight one is rerouted on. */
	public boolean hostFarFaster(String domain) {
		Double host = scheduler.measuredSpeed(HOST_DOMAIN);
		Double measured = scheduler.measuredSpeed(domain);
		return host != null && measured != null && !adequate(measured) && host >= ENGAGE_RATIO * measured;
	}

	/** Whether a platform delivering at this rate already does the job, so no rescue is owed it however much faster the host reads. */
	public boolean adequate(double bytesPerSecond) {
		return bytesPerSecond >= ADEQUATE_PLATFORM_SPEED;
	}

	/** The engagement, logged once per engaged domain: the observed ratio travels with it, so the constant above has a trail to tune against. */
	public void engaged(String domain) {
		Double host = scheduler.measuredSpeed(HOST_DOMAIN);
		Double measured = scheduler.measuredSpeed(domain);
		if (host == null || measured == null) return;
		LOGGER.info("the AutoModpack host measured {}, {}x the platform domain {} at {}; the host wire takes its traffic", ByteFormat.formatSpeed(host.longValue()),
				ratio(host, measured), domain, ByteFormat.formatSpeed(measured.longValue()));
	}

	private static String ratio(double host, double platform) {
		return String.format(Locale.ROOT, "%.1f", host / platform);
	}
}
