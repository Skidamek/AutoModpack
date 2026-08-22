package pl.skidam.automodpack_core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.skidam.automodpack_core.config.Jsons;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedSecurityPathsTest {
    @TempDir
    Path tempDirectory;

    @Test
    void relativeDirectoryMayBeCreatedAfterResolution() throws Exception {
        Jsons.SharedSecurityFields config = config("new-security");
        SharedSecurityPaths paths = assertDoesNotThrow(() -> SharedSecurityPaths.resolve(config, tempDirectory));

        paths.ensureDirectory();

        assertTrue(Files.isDirectory(tempDirectory.resolve("new-security")));
    }

    @Test
    void relativeSymlinkToOutsideIsRejected() throws Exception {
        Path outside = Files.createTempDirectory(tempDirectory.getParent(), "shared-security-outside-");
        Files.createDirectories(outside);
        Path link = tempDirectory.resolve("outside-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Files.deleteIfExists(outside);
            return;
        }

        try {
            Jsons.SharedSecurityFields config = config("outside-link/security");
            assertThrows(IllegalArgumentException.class, () -> SharedSecurityPaths.resolve(config, tempDirectory));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void directoryInsideHostModpackIsRejectedEvenWhenItDoesNotExistYet() {
        Jsons.SharedSecurityFields config = config("automodpack/host-modpack/security");

        assertThrows(IllegalArgumentException.class, () -> SharedSecurityPaths.resolve(config, tempDirectory));
    }

    private static Jsons.SharedSecurityFields config(String directory) {
        Jsons.SharedSecurityFields config = new Jsons.SharedSecurityFields();
        config.enabled = true;
        config.nodeId = "BackendA";
        config.directory = directory;
        config.fsync = false;
        return config;
    }
}
