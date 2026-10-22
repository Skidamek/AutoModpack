package pl.skidam.automodpack_core.protocol;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.cert.CRLException;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * Whether a CA-vouched leaf was revoked by its CA, from the CRL its http distribution point names. The JDK's PKIX
 * revocation checker judges that CRL (issuer, signature, scope, nextUpdate) against the issuer from the presented
 * chain, offline: the CRL comes from {@link FetchCache}, racing the http distribution point and the same URL over
 * https, and the cache stops waiting for a refetch at {@link #DEADLINE}. A leaf naming no http CRL distribution point
 * (OCSP-only and short-lived certificates) is {@link Status#GOOD} by design: there is no CRL to consult, so the CA's
 * vouch stands. Anything with a CRL that cannot be decided is {@link Status#UNKNOWN}.
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
			url = httpCrlUrl(leaf);
		} catch (IOException | RuntimeException unreadable) {
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
		X509Certificate issuer = issuer(leaf, presented);
		if (issuer == null) return CompletableFuture.completedFuture(Status.UNKNOWN);
		X509CRL cached = crl(cache.stored(sources.get(0)));
		return cache.get(sources, body -> settled(leaf, issuer, body, cached), deadline)
				.thenApply(status -> status == null ? Status.UNKNOWN : status)
				.exceptionally(error -> Status.UNKNOWN);
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

	/** The presented certificate whose key signed the leaf; a decoy carrying the issuer's name under another key is skipped. */
	private static X509Certificate issuer(X509Certificate leaf, X509Certificate[] presented) {
		if (presented == null) return null;
		for (X509Certificate candidate : presented) {
			if (candidate == null || candidate.equals(leaf) || !candidate.getSubjectX500Principal().equals(leaf.getIssuerX500Principal())) continue;
			try {
				leaf.verify(candidate.getPublicKey());
				return candidate;
			} catch (GeneralSecurityException notThisKey) {
			}
		}
		return null;
	}

	/** The leaf's status by this CRL body; null when the body is no CRL, is older than {@code cached}, or cannot settle it. */
	private static Status settled(X509Certificate leaf, X509Certificate issuer, byte[] body, X509CRL cached) {
		X509CRL crl = crl(body);
		if (crl == null || cached != null && olderThan(crl, cached)) return null;
		Status status = judge(leaf, issuer, crl);
		return status == Status.UNKNOWN ? null : status;
	}

	private static X509CRL crl(byte[] body) {
		if (body == null) return null;
		try {
			return (X509CRL) CertificateFactory.getInstance("X.509").generateCRL(new ByteArrayInputStream(body));
		} catch (CRLException | CertificateException e) {
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

	private static Status judge(X509Certificate leaf, X509Certificate issuer, X509CRL crl) {
		try {
			CertPathValidator validator = CertPathValidator.getInstance("PKIX");
			PKIXRevocationChecker revocation = (PKIXRevocationChecker) validator.getRevocationChecker();
			revocation.setOptions(EnumSet.of(PKIXRevocationChecker.Option.ONLY_END_ENTITY, PKIXRevocationChecker.Option.PREFER_CRLS, PKIXRevocationChecker.Option.NO_FALLBACK));
			PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(issuer, null)));
			parameters.addCertPathChecker(revocation);
			parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(List.of(crl))));
			validator.validate(CertificateFactory.getInstance("X.509").generateCertPath(List.of(leaf)), parameters);
			return Status.GOOD;
		} catch (CertPathValidatorException e) {
			return e.getReason() == CertPathValidatorException.BasicReason.REVOKED ? Status.REVOKED : Status.UNKNOWN;
		} catch (GeneralSecurityException e) {
			return Status.UNKNOWN;
		}
	}
}
