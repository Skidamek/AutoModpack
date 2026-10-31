package pl.skidam.automodpack_core.protocol.trust;

import java.io.ByteArrayInputStream;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Whether a CA-vouched leaf was revoked by its CA, by a CRL body the caller already judged current and in scope. The
 * JDK's PKIX revocation checker validates that CRL (issuer, signature, scope, nextUpdate) against the issuer from the
 * presented chain, offline. A listed serial in a CRL that is not scoped to a distribution point is revoked even when
 * the CRL has passed nextUpdate, because a revocation is final and PKIX refuses to judge an expired body.
 */
final class CrlVerdict {

	static final int UNUSABLE = 0;
	static final int GOOD = 1;
	static final int REVOKED = 2;
	static final int UNKNOWN = 3;

	private static final String ISSUING_DISTRIBUTION_POINT = "2.5.29.28";

	private CrlVerdict() {}

	/** The leaf's status by this CRL body; {@link #UNUSABLE} when the body is no CRL or cannot settle it. */
	static int verdict(X509Certificate leaf, X509Certificate[] presented, byte[] body) {
		X509Certificate issuer = issuer(leaf, presented);
		if (issuer == null) return UNKNOWN;
		X509CRL crl = crl(body);
		if (crl == null) return UNUSABLE;
		int status = judge(leaf, issuer, crl);
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

	private static int judge(X509Certificate leaf, X509Certificate issuer, X509CRL crl) {
		if (revokedBy(leaf, issuer, crl)) return REVOKED;
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
	 * revoked even when the CRL has passed nextUpdate. Only a CRL the issuer signed and that names no distribution
	 * point speaks here; a scoped body is left to PKIX, which enforces the scope.
	 */
	private static boolean revokedBy(X509Certificate leaf, X509Certificate issuer, X509CRL crl) {
		if (!crl.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) return false;
		if (crl.getExtensionValue(ISSUING_DISTRIBUTION_POINT) != null) return false;
		try {
			crl.verify(issuer.getPublicKey());
		} catch (GeneralSecurityException wrongKey) {
			return false;
		}
		return crl.getRevokedCertificate(leaf.getSerialNumber()) != null;
	}
}
