package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

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

	@Test
	void aCompromisedIntermediateNeverCountsAsACaVouch() throws Exception {
		X509Certificate certificate = pem(EM_OV_TLS_CA_G3C_PEM);
		var trust = new CustomizableTrustManager.SessionTrust("origin.example:25565", null);
		var manager = new CustomizableTrustManager(trust, null, trusting(certificate));
		manager.checkServerTrusted(new X509Certificate[]{certificate}, "ECDHE_ECDSA");
		CertificateException failure = manager.getDeferredFailure(null);
		assertNotNull(failure, "the CA-covering-origin step must see a failure, not a clean vouch");
		assertTrue(failure.getMessage().contains("OneCRL"), "the failure must be the OneCRL rejection, not an unrelated PKIX failure");
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
