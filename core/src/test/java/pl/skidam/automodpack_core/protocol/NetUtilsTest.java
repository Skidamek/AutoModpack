package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.security.Provider;
import java.security.Security;
import java.security.cert.X509Certificate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetUtilsTest {
	@Test
	void shortensFingerprintWithoutShowingItsMiddle() {
		String fingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

		String shortened = NetUtils.shortenFingerprint(fingerprint, 16);

		assertEquals("0123456789abcdef...0123456789abcdef", shortened);
		assertFalse(shortened.contains(fingerprint));
		assertEquals("short fingerprint", NetUtils.shortenFingerprint("short fingerprint", 16));
	}

	@Test
	void createsJdkCompatibleSelfSignedCertificateWithoutInstallingProvider(@TempDir Path directory) throws Exception {
		Provider providerBefore = Security.getProvider("BC");
		var keyPair = NetUtils.generateKeyPair();

		X509Certificate certificate = NetUtils.selfSign(keyPair);

		assertEquals(certificate.getSubjectX500Principal(), certificate.getIssuerX500Principal());
		assertEquals(keyPair.getPublic(), certificate.getPublicKey());
		assertEquals("SHA256withRSA", certificate.getSigAlgName());
		assertDoesNotThrow(() -> certificate.checkValidity());
		assertDoesNotThrow(() -> certificate.verify(keyPair.getPublic()));
		assertSame(providerBefore, Security.getProvider("BC"));

		Path certificatePath = directory.resolve("certificate.pem");
		NetUtils.saveCertificate(certificate, certificatePath);
		X509Certificate loadedCertificate = NetUtils.loadCertificate(certificatePath);
		assertEquals(NetUtils.getFingerprint(certificate), NetUtils.getFingerprint(loadedCertificate));
	}
}
