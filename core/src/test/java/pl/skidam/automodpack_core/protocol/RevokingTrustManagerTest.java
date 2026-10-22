package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

class RevokingTrustManagerTest {

	// EM OV TLS CA - G3C, private key 4. https://emudhra.we-have-a-problem.com/impacted-intermediates.json
	private static final String EM_OV_TLS_CA_G3C_PEM = """
			-----BEGIN CERTIFICATE-----
			MIIDaDCCAu6gAwIBAgIPKWkmwiejoVJjkrGUgqUEMAoGCCqGSM49BAMDMFYxCzAJ
			BgNVBAYTAklOMSUwIwYDVQQKDBxlTXVkaHJhIFRlY2hub2xvZ2llcyBMaW1pdGVk
			MSAwHgYDVQQDDBdlbVNpZ24gUm9vdCBUTFMgQ0EgLSBHMzAeFw0yNTA0MzAxNTM3
			NDFaFw00MDA0MjgxODMwMDBaMEAxCzAJBgNVBAYTAlVTMRQwEgYDVQQKDAtlTXVk
			aHJhIEluYzEbMBkGA1UEAwwSRU0gT1YgVExTIENBIC0gRzNDMHYwEAYHKoZIzj0C
			AQYFK4EEACIDYgAEE4JRzVKskpjByKrZdzId65fnCb0LTKCspV3IrVHc/J0ViaFZ
			fjpRIOHv1jHGPhg1ysrimGmmLhYx6KKBgatWYW3EXZGKvAnzqw5jz3kqpNztc4e+
			N7ulaVSfHAKycO1no4IBljCCAZIwHwYDVR0jBBgwFoAU4TqrIXzx401UrtRyt/2L
			WUIgkFQwHQYDVR0OBBYEFDTq6gzryQra+dDF4u2beMmHnhtsMB0GA1UdJQQWMBQG
			CCsGAQUFBwMBBggrBgEFBQcDAjBdBgNVHSAEVjBUMDkGCysGAQQBg44hAQABMCow
			KAYIKwYBBQUHAgEWHGh0dHA6Ly9yZXBvc2l0b3J5LmVtc2lnbi5jb20wDQYLKwYB
			BAGDjiEBAm4wCAYGZ4EMAQICMHUGCCsGAQUFBwEBBGkwZzAkBggrBgEFBQcwAYYY
			aHR0cDovL29jc3AtYy5lbXNpZ24uY29tMD8GCCsGAQUFBzAChjNodHRwOi8vcmVw
			b3NpdG9yeS5lbXNpZ24uY29tL2NlcnRzL0VNT1ZUTFNDQUczQy5wN2MwNwYDVR0f
			BDAwLjAsoCqgKIYmaHR0cDovL2NybC5lbXNpZ24uY29tLz9yb290dGxzY2FnMy5j
			cmwwDgYDVR0PAQH/BAQDAgEGMBIGA1UdEwEB/wQIMAYBAf8CAQAwCgYIKoZIzj0E
			AwMDaAAwZQIxAMSZt2xsPeumRdeSMSQ3CbzIZeQ/q7GDCWfeE1Pq+mhgA6gRM44c
			NVF3xJ+wsxJk+wIwFJbaDMokD8Ei6eNOiA8yUMYvfzQyngmrhZQMdPCK1SewrR79
			4rSb9vGqxZzelQth
			-----END CERTIFICATE-----
			""";

	@Test
	void theEmudhraIntermediatePublicKeyIsDistrusted() throws Exception {
		X509Certificate certificate = pem(EM_OV_TLS_CA_G3C_PEM);
		var manager = RevokingTrustManager.wrap(trusting(certificate));
		CertificateException thrown = assertThrows(CertificateException.class, () -> manager.checkServerTrusted(new X509Certificate[]{certificate}, "ECDHE_ECDSA"));
		assertTrue(thrown.getMessage().contains("OneCRL"));
	}

	@Test
	void aGeneratedLeafIsNotDistrusted() throws Exception {
		X509Certificate certificate = NetUtils.selfSign(NetUtils.generateKeyPair());
		var manager = RevokingTrustManager.wrap(trusting(certificate));
		assertDoesNotThrow(() -> manager.checkServerTrusted(new X509Certificate[]{certificate}, "RSA"));
	}


	/** The server sends only its leaf; the JDK completes the path from a trust-store intermediate, and OneCRL judges that path too. */
	@Test
	void aBlockedIntermediateFromTheTrustStoreIsCheckedThoughTheServerNeverSentIt() throws Exception {
		Blocked blocked = blockedEntry();
		X500Name blockedIssuer = blocked.issuer();
		BigInteger blockedSerial = blocked.serial();

		for (boolean listed : new boolean[]{false, true}) {
			KeyPair rootKeys = NetUtils.generateKeyPair();
			KeyPair intermediateKeys = NetUtils.generateKeyPair();
			X500Name rootName = listed ? blockedIssuer : new X500Name("CN=Clean Root");
			X509Certificate root = certificate(rootName, BigInteger.ONE, rootName, rootKeys, rootKeys, true);
			X500Name intermediateName = new X500Name("CN=Trust Store Intermediate");
			X509Certificate intermediate = certificate(rootName, listed ? blockedSerial : BigInteger.TWO, intermediateName, intermediateKeys, rootKeys, true);
			X509Certificate leaf = certificate(intermediateName, BigInteger.TEN, new X500Name("CN=leaf.example"), NetUtils.generateKeyPair(), intermediateKeys, false);
			assertTrue(OneCrl.blocks(intermediate) == listed);
			KeyStore store = trusting(root);
			store.setCertificateEntry("intermediate", intermediate);
			var manager = RevokingTrustManager.wrap(store);

			if (listed) {
				CertificateException thrown = assertThrows(CertificateException.class, () -> manager.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
				assertTrue(thrown.getMessage().contains("OneCRL"), thrown::toString);
			} else {
				assertDoesNotThrow(() -> manager.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
			}
		}
	}

	/**
	 * One intermediate key certified twice: by a clean root, and by a cross-certificate OneCRL blocks. The handshake
	 * stands while a path avoids the blocked certificate, and only falls once every path runs through it.
	 */
	@Test
	void aBlockedCrossCertificateRejectsOnlyWhenEveryPathRunsThroughIt() throws Exception {
		Blocked blocked = blockedEntry();
		KeyPair cleanRootKeys = NetUtils.generateKeyPair();
		KeyPair blockedRootKeys = NetUtils.generateKeyPair();
		KeyPair intermediateKeys = NetUtils.generateKeyPair();
		X500Name cleanRootName = new X500Name("CN=Clean Root");
		X500Name intermediateName = new X500Name("CN=Cross-Signed Intermediate");
		X509Certificate cleanRoot = certificate(cleanRootName, BigInteger.ONE, cleanRootName, cleanRootKeys, cleanRootKeys, true);
		X509Certificate blockedRoot = certificate(blocked.issuer(), BigInteger.ONE, blocked.issuer(), blockedRootKeys, blockedRootKeys, true);
		X509Certificate cleanIntermediate = certificate(cleanRootName, BigInteger.TWO, intermediateName, intermediateKeys, cleanRootKeys, true);
		X509Certificate crossCertificate = certificate(blocked.issuer(), blocked.serial(), intermediateName, intermediateKeys, blockedRootKeys, true);
		X509Certificate leaf = certificate(intermediateName, BigInteger.TEN, new X500Name("CN=leaf.example"), NetUtils.generateKeyPair(), intermediateKeys, false);
		assertTrue(OneCrl.blocks(crossCertificate));
		for (X509Certificate clean : List.of(cleanRoot, blockedRoot, cleanIntermediate, leaf)) assertFalse(OneCrl.blocks(clean));
		KeyStore both = trusting(cleanRoot);
		both.setCertificateEntry("blocked-root", blockedRoot);

		assertDoesNotThrow(() -> RevokingTrustManager.wrap(both).checkServerTrusted(new X509Certificate[]{leaf, crossCertificate, cleanIntermediate}, "RSA"), "a clean path exists");
		assertDoesNotThrow(() -> RevokingTrustManager.wrap(trusting(cleanRoot)).checkServerTrusted(new X509Certificate[]{leaf, cleanIntermediate, crossCertificate}, "RSA"),
				"a presented certificate no path uses is not judged");
		CertificateException thrown = assertThrows(CertificateException.class,
				() -> RevokingTrustManager.wrap(trusting(blockedRoot)).checkServerTrusted(new X509Certificate[]{leaf, crossCertificate}, "RSA"));
		assertTrue(thrown.getMessage().contains("OneCRL"), thrown::toString);
	}

	/**
	 * Six intermediates validate as presented, but exceed the five a path builder allows by default, so neither build
	 * runs: then OneCRL judges the issuer chain the leaf is signed through, never a presented certificate it does not use.
	 */
	@Test
	void anUnbuildablePathIsJudgedByTheLeafsIssuerChainOnly() throws Exception {
		Blocked blocked = blockedEntry();
		KeyPair rootKeys = NetUtils.generateKeyPair();
		X500Name rootName = new X500Name("CN=Clean Root");
		X509Certificate root = certificate(rootName, BigInteger.ONE, rootName, rootKeys, rootKeys, true);
		X509Certificate blockedRoot = certificate(blocked.issuer(), BigInteger.ONE, blocked.issuer(), rootKeys, rootKeys, true);
		DeepChain deep = deepChain(rootName, rootKeys);
		List<X509Certificate> chain = deep.certificates();
		X509Certificate unusedCrossRoot = certificate(blocked.issuer(), blocked.serial(), rootName, rootKeys, rootKeys, true);
		X509Certificate blockedTop = certificate(blocked.issuer(), blocked.serial(), new X500Name("CN=Intermediate 1"), deep.topKeys(), rootKeys, true);
		assertTrue(OneCrl.blocks(unusedCrossRoot));
		for (X509Certificate clean : chain) assertFalse(OneCrl.blocks(clean));

		List<X509Certificate> withUnused = new ArrayList<>(chain);
		withUnused.add(unusedCrossRoot);
		assertDoesNotThrow(() -> RevokingTrustManager.wrap(trusting(root)).checkServerTrusted(withUnused.toArray(X509Certificate[]::new), "RSA"), "the cross-root is never used");
		List<X509Certificate> throughBlocked = new ArrayList<>(chain);
		throughBlocked.set(chain.size() - 1, blockedTop);
		CertificateException thrown = assertThrows(CertificateException.class,
				() -> RevokingTrustManager.wrap(trusting(blockedRoot)).checkServerTrusted(throughBlocked.toArray(X509Certificate[]::new), "RSA"));
		assertTrue(thrown.getMessage().contains("OneCRL"), thrown::toString);
	}

	/**
	 * An unbuildable chain padded with certificates that share one name and key, each issuing every other one: the issuer
	 * walk visits each once, so it ends at once, and with the only anchor above them blocked it rejects.
	 */
	@Test
	void aChainPaddedWithMutuallyCrossSignedCertificatesIsWalkedOnce() throws Exception {
		Blocked blocked = blockedEntry();
		KeyPair padKeys = NetUtils.generateKeyPair();
		X500Name padName = new X500Name("CN=Padding");
		X509Certificate blockedAnchor = certificate(blocked.issuer(), blocked.serial(), padName, padKeys, NetUtils.generateKeyPair(), true);
		List<X509Certificate> padded = new ArrayList<>(deepChain(padName, padKeys).certificates());
		for (int pad = 0; pad < 12; pad++) padded.add(certificate(padName, BigInteger.valueOf(100 + pad), padName, padKeys, padKeys, true));
		assertTrue(OneCrl.blocks(blockedAnchor));

		CertificateException thrown = assertTimeoutPreemptively(Duration.ofSeconds(20),
				() -> assertThrows(CertificateException.class, () -> RevokingTrustManager.wrap(trusting(blockedAnchor)).checkServerTrusted(padded.toArray(X509Certificate[]::new), "RSA")));
		assertTrue(thrown.getMessage().contains("OneCRL"), thrown::toString);
	}

	/** A leaf under six intermediates, the topmost issued by {@code issuerName}: one more than a path builder allows by default. */
	private record DeepChain(List<X509Certificate> certificates, KeyPair topKeys) {}

	private static DeepChain deepChain(X500Name issuerName, KeyPair issuerKeys) throws Exception {
		List<X509Certificate> chain = new ArrayList<>();
		KeyPair topKeys = null;
		for (int depth = 1; depth <= 7; depth++) {
			KeyPair keys = NetUtils.generateKeyPair();
			X500Name name = new X500Name(depth == 7 ? "CN=leaf.example" : "CN=Intermediate " + depth);
			chain.add(0, certificate(issuerName, BigInteger.valueOf(depth + 1), name, keys, issuerKeys, depth < 7));
			if (depth == 1) topKeys = keys;
			issuerKeys = keys;
			issuerName = name;
		}
		return new DeepChain(chain, topKeys);
	}

	/** An issuer and serial the bundled OneCRL snapshot blocks, with a positive one-byte-leading serial a generated certificate can carry. */
	private record Blocked(X500Name issuer, BigInteger serial) {}

	private static Blocked blockedEntry() {
		String blocked = OneCrl.loadBundledSnapshot().issuerSerial().stream().filter(key -> Integer.parseInt(key.substring(key.indexOf('|') + 1, key.indexOf('|') + 3), 16) < 0x80)
				.findFirst().orElseThrow();
		HexFormat hex = HexFormat.of();
		return new Blocked(X500Name.getInstance(hex.parseHex(blocked.substring(0, blocked.indexOf('|')))), new BigInteger(hex.parseHex(blocked.substring(blocked.indexOf('|') + 1))));
	}

	private static X509Certificate certificate(X500Name issuer, BigInteger serial, X500Name subject, KeyPair subjectKeys, KeyPair issuerKeys, boolean ca) throws Exception {
		JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer, serial, Date.from(Instant.now().minusSeconds(3600)), Date.from(Instant.now().plusSeconds(86_400)), subject,
				subjectKeys.getPublic());
		if (ca) builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
		return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(issuerKeys.getPrivate())));
	}

	private static KeyStore trusting(X509Certificate certificate) throws Exception {
		KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
		store.load(null, null);
		store.setCertificateEntry("test-anchor", certificate);
		return store;
	}

	private static X509Certificate pem(String pem) throws Exception {
		try (var in = new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII))) {
			return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
		}
	}
}
