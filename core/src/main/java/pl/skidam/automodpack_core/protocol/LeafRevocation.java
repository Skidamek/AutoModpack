package pl.skidam.automodpack_core.protocol;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;

import pl.skidam.automodpack_core.utils.FetchCache;

/**
 * Whether a CA-vouched leaf was revoked by its CA, from the CRL its http distribution point names. The data comes from
 * {@link FetchCache}, racing the http distribution point and the same URL over https, and the cache stops waiting for a
 * refetch at {@link #DEADLINE}. A copy that still settles the leaf answers at once and its refresh runs without holding
 * the connection; a revocation that lands after the verdict ends the session through {@code onRevoked}, because a
 * revocation is final. The judging itself is the boundary's ({@link Trust}); this class reads the certificate's
 * distribution point and keeps the newest copy of the CRL.
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
			url = httpCrlUrl(leaf);
		} catch (IOException | RuntimeException unreadable) {
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
	private static Status settled(X509Certificate leaf, X509Certificate[] presented, byte[] body, byte[] cachedBody) {
		X509CRL crl = crl(body);
		if (crl == null) return null;
		X509CRL cached = crl(cachedBody);
		if (cached != null && olderThan(crl, cached)) return null;
		return switch (Trust.crlVerdict(leaf, presented, body)) {
			case Trust.REVOKED -> Status.REVOKED;
			case Trust.GOOD -> Status.GOOD;
			default -> null;
		};
	}

	/** The first http URI among the leaf's full-name CRL distribution points, scheme lowercased; null when it names none. */
	static String httpCrlUrl(X509Certificate leaf) throws IOException {
		byte[] extension = leaf.getExtensionValue(Extension.cRLDistributionPoints.getId());
		if (extension == null) return null;
		for (DistributionPoint point : CRLDistPoint.getInstance(JcaX509ExtensionUtils.parseExtensionValue(extension)).getDistributionPoints()) {
			DistributionPointName name = point.getDistributionPoint();
			if (name == null || name.getType() != DistributionPointName.FULL_NAME) continue;
			for (GeneralName general : GeneralNames.getInstance(name.getName()).getNames()) {
				if (general.getTagNo() != GeneralName.uniformResourceIdentifier) continue;
				String uri = general.getName().toString();
				if (uri.toLowerCase(Locale.ROOT).startsWith(HTTP)) return HTTP + uri.substring(HTTP.length());
			}
		}
		return null;
	}

	private static X509CRL crl(byte[] body) {
		if (body == null) return null;
		try {
			return (X509CRL) CertificateFactory.getInstance("X.509").generateCRL(new ByteArrayInputStream(body));
		} catch (GeneralSecurityException notACrl) {
			return null;
		}
	}

	/** Issued before {@code cached}, by thisUpdate or, when both carry one, by CRL number. */
	private static boolean olderThan(X509CRL crl, X509CRL cached) {
		if (crl.getThisUpdate().before(cached.getThisUpdate())) return true;
		BigInteger number = crlNumber(crl);
		BigInteger cachedNumber = crlNumber(cached);
		return number != null && cachedNumber != null && number.compareTo(cachedNumber) < 0;
	}

	private static BigInteger crlNumber(X509CRL crl) {
		byte[] extension = crl.getExtensionValue(Extension.cRLNumber.getId());
		if (extension == null) return null;
		try {
			return ASN1Integer.getInstance(JcaX509ExtensionUtils.parseExtensionValue(extension)).getValue();
		} catch (IOException | RuntimeException unreadable) {
			return null;
		}
	}
}
