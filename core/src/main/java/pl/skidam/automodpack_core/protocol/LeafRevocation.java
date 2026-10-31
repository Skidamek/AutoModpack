package pl.skidam.automodpack_core.protocol;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import pl.skidam.automodpack_core.utils.FetchCache;

/**
 * Whether a CA-vouched leaf was revoked by its CA, from the CRL its http distribution point names. The data comes from
 * {@link FetchCache}, racing the http distribution point and the same URL over https, and the cache stops waiting for a
 * refetch at {@link #DEADLINE}. A copy that still settles the leaf answers at once and its refresh runs without holding
 * the connection; a revocation that lands after the verdict ends the session through {@code onRevoked}, because a
 * revocation is final. The verdict itself is the boundary's ({@link Trust}); this class only fetches and waits.
 */
final class LeafRevocation {

	enum Status {
		GOOD, REVOKED, UNKNOWN
	}

	static final Duration DEADLINE = Duration.ofSeconds(4);
	private static final String HTTP = "http://";

	private LeafRevocation() {}

	static CompletableFuture<Status> check(X509Certificate leaf, X509Certificate[] presented) {
		return check(leaf, presented, FetchCache.shared(), DEADLINE, () -> {});
	}

	static CompletableFuture<Status> check(X509Certificate leaf, X509Certificate[] presented, Runnable onRevoked) {
		return check(leaf, presented, FetchCache.shared(), DEADLINE, onRevoked);
	}

	static CompletableFuture<Status> check(X509Certificate leaf, X509Certificate[] presented, FetchCache cache, Duration deadline) {
		return check(leaf, presented, cache, deadline, () -> {});
	}

	static CompletableFuture<Status> check(X509Certificate leaf, X509Certificate[] presented, FetchCache cache, Duration deadline, Runnable onRevoked) {
		String url;
		try {
			url = Trust.crlUrl(leaf);
		} catch (RuntimeException unreadable) {
			return CompletableFuture.completedFuture(Status.UNKNOWN);
		}
		if (url == null) return CompletableFuture.completedFuture(Status.GOOD);
		return check(leaf, presented, List.of(url, "https://" + url.substring(HTTP.length())), cache, deadline, onRevoked);
	}

	/**
	 * Races the sources of one CRL, cached under the first; only a CRL that settles the leaf's status (signed by its
	 * issuer, in scope, before its nextUpdate) and is no older than the cached copy wins, so neither a fast bad answer
	 * nor a replayed older CRL from one source ever beats a good one. A copy that still settles is the answer, and the
	 * race runs behind it.
	 */
	static CompletableFuture<Status> check(X509Certificate leaf, X509Certificate[] presented, List<String> sources, FetchCache cache, Duration deadline) {
		return check(leaf, presented, sources, cache, deadline, () -> {});
	}

	static CompletableFuture<Status> check(X509Certificate leaf, X509Certificate[] presented, List<String> sources, FetchCache cache, Duration deadline, Runnable onRevoked) {
		byte[] stored = cache.stored(sources.get(0));
		Status known = settled(leaf, presented, stored, null);
		if (known != null) {
			refresh(leaf, presented, sources, cache, stored, known, onRevoked);
			return CompletableFuture.completedFuture(known);
		}
		return cache.get(sources, body -> settled(leaf, presented, body, stored), deadline).thenApply(status -> status == null ? Status.UNKNOWN : status);
	}

	/**
	 * A good verdict is only true of the CRL it came from, so a copy past our freshness window is refreshed without
	 * holding the connection; a revocation found there is final, so it ends the session that already accepted the leaf.
	 */
	private static void refresh(X509Certificate leaf, X509Certificate[] presented, List<String> sources, FetchCache cache, byte[] stored, Status known, Runnable onRevoked) {
		if (known == Status.REVOKED) return;
		cache.get(sources, body -> settled(leaf, presented, body, stored), Duration.ofMinutes(2)).thenAccept(status -> {
			if (status == Status.REVOKED) onRevoked.run();
		});
	}

	/** The leaf's status by this CRL body; null when the body is no CRL, is older than {@code cached}, or cannot settle it. */
	private static Status settled(X509Certificate leaf, X509Certificate[] presented, byte[] body, byte[] cached) {
		return switch (Trust.crlVerdict(leaf, presented, body, cached)) {
			case Trust.REVOKED -> Status.REVOKED;
			case Trust.GOOD -> Status.GOOD;
			default -> null;
		};
	}
}
