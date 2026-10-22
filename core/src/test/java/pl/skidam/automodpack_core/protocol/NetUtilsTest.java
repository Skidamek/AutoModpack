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
}
