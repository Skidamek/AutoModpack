package pl.skidam.automodpack_core.protocol;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import pl.skidam.automodpack_core.utils.FetchCache;

/**
 * Whether a CA-vouched leaf was revoked by its CA, from the CRL its http distribution point names. The data comes from
 * {@link FetchCache}, racing the http distribution point and the same URL over https, and the cache stops waiting for a
 * refetch at {@link #DEADLINE}. The verdict itself is the boundary's ({@link Trust}); this class only fetches and waits.
 * A leaf naming no http CRL distribution point (OCSP-only and short-lived certificates) is {@link Status#GOOD} by
 * design: there is no CRL to consult, so the CA's vouch stands. Anything with a CRL that cannot be decided is
 * {@link Status#UNKNOWN}.
 */
final class LeafRevocation {

	enum Status {
		GOOD, REVOKED, UNKNOWN
	}

	static final Duration DEADLINE = Duration.ofSeconds(4);
	private static final String HTTP = "http://";

	private LeafRevocation() {}

	static CompletableFuture<Status> check(X509Certificate leaf, X509Certificate[] presented) {
		return check(leaf, presented, FetchCache.shared(), DEADLINE);
	}

	static CompletableFuture<Status> check(X509Certificate leaf, X509Certificate[] presented, FetchCache cache, Duration deadline) {
		String url;
		try {
			url = Trust.crlUrl(leaf);
		} catch (RuntimeException unreadable) {
			return CompletableFuture.completedFuture(Status.UNKNOWN);
		}
		if (url == null) return CompletableFuture.completedFuture(Status.GOOD);
		return check(leaf, presented, List.of(url, "https://" + url.substring(HTTP.length())), cache, deadline);
	}

	/**
	 * Races the sources of one CRL, cached under the first; only a CRL that settles the leaf's status (signed by its
	 * issuer, in scope, before its nextUpdate) and is no older than the cached copy wins, so neither a fast bad answer
	 * nor a replayed older CRL from one source ever beats a good one.
	 */
	static CompletableFuture<Status> check(X509Certificate leaf, X509Certificate[] presented, List<String> sources, FetchCache cache, Duration deadline) {
		byte[] cached = cache.stored(sources.get(0));
		return cache.get(sources, body -> settled(leaf, presented, body, cached), deadline).thenApply(status -> status == null ? Status.UNKNOWN : status);
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
