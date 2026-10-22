package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.Provider;
import java.security.Security;
import java.security.cert.X509Certificate;

import org.junit.jupiter.api.Assumptions;
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
	void privateKeyIsOwnerOnlyOnPosix(@TempDir Path directory) throws Exception {
		Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		Path keyPath = directory.resolve("private-key.pem");

		NetUtils.savePrivateKey(NetUtils.generateKeyPair().getPrivate(), keyPath);
		assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(keyPath));
	}

	@Test
	void privateKeyIsOwnerOnlyOnAcl(@TempDir Path directory) throws Exception {
		Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("acl"));
		Path keyPath = directory.resolve("private-key.pem");
		NetUtils.savePrivateKey(NetUtils.generateKeyPair().getPrivate(), keyPath);
		AclFileAttributeView view = Files.getFileAttributeView(keyPath, AclFileAttributeView.class);
		Assumptions.assumeTrue(view != null);
		UserPrincipal owner = Files.getOwner(keyPath);
		for (AclEntry entry : view.getAcl()) {
			if (entry.type() == AclEntryType.ALLOW) assertEquals(owner, entry.principal(), entry::toString);
		}
		assertTrue(view.getAcl().stream().anyMatch(entry -> entry.type() == AclEntryType.ALLOW && owner.equals(entry.principal())));
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

	@Test
	void ensureSelfSignedTlsPairWritesAMatchingPairAndLeavesItAlone(@TempDir Path directory) throws Exception {
		Path certFile = directory.resolve("certificate.crt");
		Path keyFile = directory.resolve("private-key.pem");
		NetUtils.ensureSelfSignedTlsPair(certFile, keyFile);
		assertTrue(Files.isRegularFile(certFile));
		assertTrue(Files.isRegularFile(keyFile));
		NetUtils.validateKeyMatchesCertificate(NetUtils.loadPrivateKey(keyFile), NetUtils.loadCertificate(certFile));
		String fingerprint = NetUtils.getFingerprint(NetUtils.loadCertificate(certFile));
		NetUtils.ensureSelfSignedTlsPair(certFile, keyFile);
		assertEquals(fingerprint, NetUtils.getFingerprint(NetUtils.loadCertificate(certFile)));
	}

	@Test
	void ensureSelfSignedTlsPairKeepsAnExistingKeyAndSignsACertificateWithIt(@TempDir Path directory) throws Exception {
		Path certFile = directory.resolve("certificate.crt");
		Path keyFile = directory.resolve("private-key.pem");
		NetUtils.savePrivateKey(NetUtils.generateKeyPair().getPrivate(), keyFile);
		byte[] existingKey = Files.readAllBytes(keyFile);
		NetUtils.ensureSelfSignedTlsPair(certFile, keyFile);
		assertArrayEquals(existingKey, Files.readAllBytes(keyFile));
		NetUtils.validateKeyMatchesCertificate(NetUtils.loadPrivateKey(keyFile), NetUtils.loadCertificate(certFile));
	}

	@Test
	void ensureSelfSignedTlsPairRefusesAnUnreadableKeyWithoutTouchingIt(@TempDir Path directory) throws Exception {
		Path certFile = directory.resolve("certificate.crt");
		Path keyFile = directory.resolve("private-key.pem");
		Files.writeString(keyFile, "not a key");
		assertThrows(IllegalStateException.class, () -> NetUtils.ensureSelfSignedTlsPair(certFile, keyFile));
		assertEquals("not a key", Files.readString(keyFile));
		assertFalse(Files.exists(certFile));
	}

	@Test
	void ensureSelfSignedTlsPairRefusesWhenOnlyTheCertExists(@TempDir Path directory) throws Exception {
		Path certFile = directory.resolve("certificate.crt");
		Path keyFile = directory.resolve("private-key.pem");
		NetUtils.ensureSelfSignedTlsPair(certFile, keyFile);
		String fingerprint = NetUtils.getFingerprint(NetUtils.loadCertificate(certFile));
		Files.delete(keyFile);
		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> NetUtils.ensureSelfSignedTlsPair(certFile, keyFile));
		assertTrue(failure.getMessage().contains("without a private key"));
		assertFalse(Files.exists(keyFile));
		assertEquals(fingerprint, NetUtils.getFingerprint(NetUtils.loadCertificate(certFile)));
	}
}
