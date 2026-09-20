package pl.skidam.automodpack_core.protocol.netty;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One server's request activity for the {@code activity} command: exact counters since startup plus a bounded ring of
 * the transfers that moved bytes or failed. Unchanged (304) checks are counted but never ringed - on a healthy server
 * they are wallpaper, and the ring must read as the story of transfers. Everything lives in memory and is wiped on
 * restart; the command is the only reader and the server log stays for errors.
 */
public final class ActivityTracker {
	// 256 completed transfers is ≈25 KB resident: the useful window is "what happened today", not an audit log.
	private static final int RING_CAPACITY = 256;
	private static final int STATUS_UNCHANGED = 304;
	/** A response that never finished because the connection died mid-stream. */
	public static final int STATUS_DROPPED = 0;

	/** One request from head-parse to fully-written response. Identity semantics; never on a Gson graph. */
	public static final class Span {
		public final long startMillis = System.currentTimeMillis();
		public final long startNanos = System.nanoTime();
		public final String address;
		public String routeKey;
		public String actor;
		/** The response size when the route's length is known up front, so in-flight progress reads as a fraction. */
		public long totalBytes;
		private long bytes;
		private boolean completed;

		Span(String address) {
			this.address = address;
		}
	}

	/** One finished request; displayName resolves the sha1 route key against the current generation. */
	public record Entry(long startMillis, long endMillis, long bytes, long totalBytes, int status, String routeKey, String displayName, String actor, String address) {}

	public record PlayerStats(String name, long requests, long bytes, long lastMillis) {}

	public record Snapshot(long startedMillis, long totalRequests, long totalBytes, long unchangedChecks, long unauthorized, long lastUnauthorizedMillis, long writeThroughput, List<PlayerStats> players,
			List<Entry> inFlight, List<Entry> recent) {}

	private record Completed(long startMillis, long endMillis, long bytes, long totalBytes, int status, String routeKey, String actor, String address) {}

	private final long startedMillis = System.currentTimeMillis();
	private final Object lock = new Object();
	private final ArrayDeque<Completed> ring = new ArrayDeque<>();
	private final List<Span> inFlight = new ArrayList<>();
	private final Map<String, PlayerStats> players = new HashMap<>();
	private long totalRequests;
	private long totalBytes;
	private long unchangedChecks;
	private long unauthorized;
	private long lastUnauthorizedMillis;
	private long writeThroughput;

	/** Starts one span; the reference is only handed to the connection's own call chain. */
	public Span start(String address) {
		Span span = new Span(address);
		synchronized (lock) {
			inFlight.add(span);
		}
		return span;
	}

	/** Reports streamed bytes so an in-flight entry shows progress. */
	public void progress(Span span, long bytes) {
		synchronized (lock) {
			span.bytes = bytes;
		}
	}

	/** Ends one span; re-completion (a drop racing the normal finish) is ignored. */
	public void complete(Span span, int status, long bytes) {
		synchronized (lock) {
			if (span.completed) return;
			span.completed = true;
			inFlight.remove(span);
			long endMillis = System.currentTimeMillis();
			totalRequests++;
			totalBytes += bytes;
			if (status == STATUS_UNCHANGED) {
				unchangedChecks++;
			} else {
				if (status == 401) {
					unauthorized++;
					lastUnauthorizedMillis = endMillis;
				}
				ring.addLast(new Completed(span.startMillis, endMillis, bytes, span.totalBytes, status, span.routeKey, span.actor, span.address));
				while (ring.size() > RING_CAPACITY) ring.removeFirst();
			}
			if (span.actor != null) {
				players.merge(span.actor, new PlayerStats(span.actor, 1, bytes, endMillis),
						(previous, ignored) -> new PlayerStats(previous.name, previous.requests + 1, previous.bytes + bytes, endMillis));
			}
		}
	}

	/** Ends a span whose connection died mid-stream. */
	public void completeDropped(Span span) {
		long bytes;
		synchronized (lock) {
			bytes = span.bytes;
		}
		complete(span, STATUS_DROPPED, bytes);
	}

	/** Publishes the shaper's current outbound rate for the summary line; zero hides the field. */
	public void writeThroughput(long bytesPerSecond) {
		synchronized (lock) {
			writeThroughput = bytesPerSecond;
		}
	}

	/** The shaper's most recent outbound rate; the codec choice reads it to tell a fast link from a shaped one. */
	public long writeThroughput() {
		synchronized (lock) {
			return writeThroughput;
		}
	}

	/** The command's read model; names resolve object hashes against the names of the current generation. */
	public Snapshot snapshot(Map<String, String> names) {
		synchronized (lock) {
			List<Entry> inFlightEntries = new ArrayList<>();
			for (Span span : inFlight) {
				inFlightEntries.add(new Entry(span.startMillis, 0, span.bytes, span.totalBytes, STATUS_DROPPED, span.routeKey, displayName(span.routeKey, names), span.actor, span.address));
			}
			List<Entry> recent = new ArrayList<>(ring.size());
			for (Completed completed : ring) {
				recent.add(new Entry(completed.startMillis(), completed.endMillis(), completed.bytes(), completed.totalBytes(), completed.status(), completed.routeKey(),
						displayName(completed.routeKey(), names), completed.actor(), completed.address()));
			}
			Collections.reverse(recent);
			List<PlayerStats> playerStats = players.values().stream().sorted(Comparator.comparingLong(PlayerStats::bytes).reversed()).toList();
			return new Snapshot(startedMillis, totalRequests, totalBytes, unchangedChecks, unauthorized, lastUnauthorizedMillis, writeThroughput, playerStats, inFlightEntries, recent);
		}
	}

	/** head/journal speak for themselves; a hash resolves to its pack path when the current generation knows it, else shortens. */
	private static String displayName(String routeKey, Map<String, String> names) {
		if (routeKey == null) return "-";
		String name = names.get(routeKey);
		if (name != null) return name;
		return routeKey.length() == 40 ? routeKey.substring(0, 10) + "…" : routeKey;
	}
}
