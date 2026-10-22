package pl.skidam.automodpack_core.protocol;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.*;

class NetUtilsTest {

    @Test
    void privateKeyIsOwnerOnlyOnPosix(@TempDir Path directory) throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path keyPath = directory.resolve("private-key.pem");

        NetUtils.savePrivateKey(NetUtils.generateKeyPair().getPrivate(), keyPath);
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(keyPath));
        try (var siblings = Files.list(directory)) {
            assertEquals(1, siblings.count(), "the temporary must not be left behind");
        }
    }

    @Test
    void privateKeyOverwriteStaysOwnerOnlyOnPosix(@TempDir Path directory) throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path keyPath = directory.resolve("private-key.pem");
        Files.writeString(keyPath, "old");
        Files.setPosixFilePermissions(keyPath, PosixFilePermissions.fromString("rw-r--r--"));

        NetUtils.savePrivateKey(NetUtils.generateKeyPair().getPrivate(), keyPath);
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(keyPath));
        assertTrue(Files.readString(keyPath).startsWith("-----BEGIN PRIVATE KEY-----"));
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
    void ensureSelfSignedTlsPairWritesAMatchingPairAndLeavesItAlone(@TempDir Path directory) throws Exception {
        Path certFile = directory.resolve("cert.crt");
        Path keyFile = directory.resolve("key.pem");
        NetUtils.ensureSelfSignedTlsPair(certFile, keyFile);
        assertTrue(Files.isRegularFile(certFile));
        assertTrue(Files.isRegularFile(keyFile));
        assertPairMatches(NetUtils.loadPrivateKey(keyFile), NetUtils.loadCertificate(certFile));

        String fingerprint = NetUtils.getFingerprint(NetUtils.loadCertificate(certFile));
        NetUtils.ensureSelfSignedTlsPair(certFile, keyFile);
        assertEquals(fingerprint, NetUtils.getFingerprint(NetUtils.loadCertificate(certFile)));
    }

    @Test
    void ensureSelfSignedTlsPairKeepsAnExistingKeyAndSignsACertificateWithIt(@TempDir Path directory) throws Exception {
        Path certFile = directory.resolve("cert.crt");
        Path keyFile = directory.resolve("key.pem");
        NetUtils.savePrivateKey(NetUtils.generateKeyPair().getPrivate(), keyFile);
        byte[] existingKey = Files.readAllBytes(keyFile);

        NetUtils.ensureSelfSignedTlsPair(certFile, keyFile);
        assertArrayEquals(existingKey, Files.readAllBytes(keyFile));
        assertPairMatches(NetUtils.loadPrivateKey(keyFile), NetUtils.loadCertificate(certFile));
    }

    @Test
    void ensureSelfSignedTlsPairRefusesAnUnreadableKeyWithoutTouchingIt(@TempDir Path directory) throws Exception {
        Path certFile = directory.resolve("cert.crt");
        Path keyFile = directory.resolve("key.pem");
        Files.writeString(keyFile, "not a key");

        assertThrows(IllegalStateException.class, () -> NetUtils.ensureSelfSignedTlsPair(certFile, keyFile));
        assertEquals("not a key", Files.readString(keyFile));
        assertFalse(Files.exists(certFile));
    }

    @Test
    void ensureSelfSignedTlsPairRefusesWhenOnlyTheCertExists(@TempDir Path directory) throws Exception {
        Path certFile = directory.resolve("cert.crt");
        Path keyFile = directory.resolve("key.pem");
        NetUtils.ensureSelfSignedTlsPair(certFile, keyFile);
        String fingerprint = NetUtils.getFingerprint(NetUtils.loadCertificate(certFile));
        Files.delete(keyFile);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> NetUtils.ensureSelfSignedTlsPair(certFile, keyFile));
        assertTrue(failure.getMessage().contains("without a private key"));
        assertFalse(Files.exists(keyFile));
        assertEquals(fingerprint, NetUtils.getFingerprint(NetUtils.loadCertificate(certFile)));
    }

    private static void assertPairMatches(PrivateKey key, X509Certificate certificate) throws Exception {
        byte[] challenge = "automodpack".getBytes();
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(challenge);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(challenge);
        assertTrue(verifier.verify(signature), "the certificate does not belong to the key");
    }
}
