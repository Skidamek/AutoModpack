package pl.skidam.automodpack_core.protocol.trust;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a CA-vouched leaf was revoked by its CA, from a CRL body the caller fetched. The JDK's PKIX revocation
 * checker judges that CRL (issuer, signature, scope, nextUpdate) against the issuer from the presented chain, offline.
 * A revocation is final, so a listed serial is revoked even when the CRL has passed nextUpdate and PKIX would refuse to
 * judge it. A leaf naming no http CRL distribution point (OCSP-only and short-lived certificates) has nothing to
 * consult, so the CA's vouch stands. The two extension reads use a small DER reader because this package may resolve
 * only the JDK.
 */
final class CrlVerdict {

	static final int UNUSABLE = 0;
	static final int GOOD = 1;
	static final int REVOKED = 2;
	static final int UNKNOWN = 3;

	private static final String HTTP = "http://";
	private static final String HTTPS = "https://";
	private static final String CRL_DISTRIBUTION_POINTS = "2.5.29.31";
	private static final String ISSUING_DISTRIBUTION_POINT = "2.5.29.28";
	private static final String CRL_NUMBER = "2.5.29.20";
	private static final int CONTEXT_0 = 0xA0;
	private static final int UNIFORM_RESOURCE_IDENTIFIER = 0x86;

	private CrlVerdict() {}

	/** The first http URI among the leaf's full-name CRL distribution points, scheme lowercased, or null when it names none. */
	static String httpUrl(X509Certificate leaf) {
		byte[] extension = leaf.getExtensionValue(CRL_DISTRIBUTION_POINTS);
		if (extension == null) return null;
		Cursor points = sequence(extension);
		while (points.has()) {
			Cursor point = points.next();
			if (!point.has() || point.peekTag() != CONTEXT_0) continue;
			String uri = uri(point.next());
			if (uri != null) return uri;
		}
		return null;
	}

	/** The leaf's status by this CRL body; {@link #UNUSABLE} when the body is no CRL, older than {@code cachedBody}, or cannot settle it. */
	static int verdict(X509Certificate leaf, X509Certificate[] presented, byte[] body, byte[] cachedBody) {
		X509Certificate issuer = issuer(leaf, presented);
		if (issuer == null) return UNKNOWN;
		X509CRL cached = crl(cachedBody);
		X509CRL crl = crl(body);
		if (crl == null || cached != null && olderThan(crl, cached)) return UNUSABLE;
		int status = judge(leaf, issuer, crl, httpUrl(leaf));
		return status == UNKNOWN ? UNUSABLE : status;
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
		byte[] extension = crl.getExtensionValue(CRL_NUMBER);
		if (extension == null) return null;
		try {
			return unwrap(extension).next().integer();
		} catch (RuntimeException unreadable) {
			return null;
		}
	}

	private static int judge(X509Certificate leaf, X509Certificate issuer, X509CRL crl, String url) {
		if (revokedBy(leaf, issuer, crl, url)) return REVOKED;
		try {
			CertPathValidator validator = CertPathValidator.getInstance("PKIX");
			PKIXRevocationChecker revocation = (PKIXRevocationChecker) validator.getRevocationChecker();
			revocation.setOptions(EnumSet.of(PKIXRevocationChecker.Option.ONLY_END_ENTITY, PKIXRevocationChecker.Option.PREFER_CRLS, PKIXRevocationChecker.Option.NO_FALLBACK));
			PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(issuer, null)));
			parameters.addCertPathChecker(revocation);
			parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(List.of(crl))));
			validator.validate(CertificateFactory.getInstance("X.509").generateCertPath(List.of(leaf)), parameters);
			return GOOD;
		} catch (CertPathValidatorException e) {
			return e.getReason() == CertPathValidatorException.BasicReason.REVOKED ? REVOKED : UNKNOWN;
		} catch (GeneralSecurityException e) {
			return UNKNOWN;
		}
	}

	/**
	 * A revocation is final: an entry stays in every later CRL until the certificate expires, so a listed serial is
	 * revoked even when the CRL itself has passed nextUpdate. The list only speaks for an issuer whose signature is on
	 * it, and only for a distribution point it is scoped to, so a body for another shard never blocks a certificate.
	 */
	private static boolean revokedBy(X509Certificate leaf, X509Certificate issuer, X509CRL crl, String url) {
		if (!crl.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) return false;
		if (!covers(crl, url)) return false;
		try {
			crl.verify(issuer.getPublicKey());
		} catch (GeneralSecurityException wrongKey) {
			return false;
		}
		return crl.getRevokedCertificate(leaf.getSerialNumber()) != null;
	}

	/** Whether the CRL is scoped to the leaf's distribution point; a CRL without one covers every point of its issuer. */
	private static boolean covers(X509CRL crl, String url) {
		byte[] extension = crl.getExtensionValue(ISSUING_DISTRIBUTION_POINT);
		if (extension == null) return true;
		if (url == null) return false;
		try {
			Cursor point = sequence(extension);
			if (!point.has() || point.peekTag() != CONTEXT_0) return true;
			String scope = uri(point.next());
			return scope != null && bare(scope).equals(bare(url));
		} catch (RuntimeException unreadable) {
			return false;
		}
	}

	private static String uri(Cursor name) {
		if (name.tag() != CONTEXT_0) return null;
		Cursor names = name.next();
		while (names.has()) {
			boolean uri = names.peekTag() == UNIFORM_RESOURCE_IDENTIFIER;
			Cursor general = names.next();
			if (!uri) continue;
			String value = general.ascii();
			if (value.toLowerCase(Locale.ROOT).startsWith(HTTP)) return HTTP + value.substring(HTTP.length());
		}
		return null;
	}

	private static String bare(String url) {
		if (url.startsWith(HTTP)) return url.substring(HTTP.length());
		return url.startsWith(HTTPS) ? url.substring(HTTPS.length()) : url;
	}

	/** The contents of an extension value's outer OCTET STRING. */
	private static Cursor unwrap(byte[] extensionValue) {
		return new Cursor(extensionValue).next();
	}

	/** The contents of the SEQUENCE an extension value wraps. */
	private static Cursor sequence(byte[] extensionValue) {
		return unwrap(extensionValue).next();
	}

	/** A bounds-checked walk over one DER value and its children; a malformed byte array throws instead of reading past it. */
	private static final class Cursor {
		private final byte[] bytes;
		private final int tag;
		private final int start;
		private final int end;
		private int at;

		Cursor(byte[] bytes) {
			this(bytes, -1, 0, bytes.length);
		}

		private Cursor(byte[] bytes, int tag, int start, int end) {
			this.bytes = bytes;
			this.tag = tag;
			this.start = start;
			this.end = end;
			this.at = start;
		}

		boolean has() {
			return at < end;
		}

		int tag() {
			return tag;
		}

		int peekTag() {
			return at < end ? bytes[at] & 0xFF : -1;
		}

		Cursor next() {
			if (at >= end) throw new IllegalArgumentException("Truncated DER");
			int childTag = bytes[at++] & 0xFF;
			int length = length();
			int contentStart = at;
			int contentEnd = contentStart + length;
			if (contentEnd > end) throw new IllegalArgumentException("Truncated DER");
			at = contentEnd;
			return new Cursor(bytes, childTag, contentStart, contentEnd);
		}

		private int length() {
			if (at >= end) throw new IllegalArgumentException("Truncated DER");
			int first = bytes[at++] & 0xFF;
			if ((first & 0x80) == 0) return first;
			int count = first & 0x7F;
			if (count == 0 || count > 4) throw new IllegalArgumentException("Unsupported DER length");
			int length = 0;
			for (int i = 0; i < count; i++) {
				if (at >= end) throw new IllegalArgumentException("Truncated DER");
				length = (length << 8) | (bytes[at++] & 0xFF);
			}
			return length;
		}

		String ascii() {
			char[] chars = new char[end - start];
			for (int i = 0; i < chars.length; i++) chars[i] = (char) (bytes[start + i] & 0x7F);
			return new String(chars);
		}

		BigInteger integer() {
			return new BigInteger(Arrays.copyOfRange(bytes, start, end));
		}
	}
}
