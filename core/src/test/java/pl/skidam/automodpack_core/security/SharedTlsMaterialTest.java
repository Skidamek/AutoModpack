package pl.skidam.automodpack_core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.NetUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SharedTlsMaterialTest {
    @TempDir
    Path tempDirectory;

    @Test
    void concurrentJvmLikeReadersCreateOneValidFingerprint() throws Exception {
        SharedSecurityPaths paths = paths(true);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> SharedTlsMaterial.loadOrCreate(paths).fingerprint());
        }
        List<Future<String>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        String fingerprint = futures.get(0).get();
        for (Future<String> future : futures) {
            assertEquals(fingerprint, future.get());
        }
        NetUtils.validateCertificateAndPrivateKey(paths.certificateFile(), paths.privateKeyFile());
    }

    @Test
    void existingValidPairIsNotReplaced() throws Exception {
        SharedSecurityPaths paths = paths(true);
        SharedTlsMaterial first = SharedTlsMaterial.loadOrCreate(paths);
        byte[] certificate = Files.readAllBytes(paths.certificateFile());
        byte[] privateKey = Files.readAllBytes(paths.privateKeyFile());
        SharedTlsMaterial second = SharedTlsMaterial.loadOrCreate(paths);
        assertEquals(first.fingerprint(), second.fingerprint());
        assertArrayEquals(certificate, Files.readAllBytes(paths.certificateFile()));
        assertArrayEquals(privateKey, Files.readAllBytes(paths.privateKeyFile()));
    }

    @Test
    void missingPairWithAutoGenerationDisabledFailsClosed() {
        SharedSecurityPaths paths = paths(false);
        Exception exception = assertThrows(Exception.class, () -> SharedTlsMaterial.loadOrCreate(paths));
        assertTrue(exception.getMessage().contains("autoGenerateCertificate=false"));
    }

    @Test
    void incompletePairIsNotSilentlyRegenerated() throws Exception {
        SharedSecurityPaths paths = paths(true);
        SharedTlsMaterial.loadOrCreate(paths);
        Files.delete(paths.privateKeyFile());
        Exception exception = assertThrows(Exception.class, () -> SharedTlsMaterial.loadOrCreate(paths));
        assertTrue(exception.getMessage().contains("incomplete") || exception.getMessage().contains("invalid"));
    }

    @Test
    void readerCallbackRunsWhileTlsLockIsHeld() throws Exception {
        SharedSecurityPaths paths = paths(true);

        String fingerprint = SharedTlsMaterial.withLockedMaterial(paths, material -> {
            Exception exception = assertThrows(Exception.class,
                    () -> SharedSecurityFileLock.withExclusiveLock(paths.tlsLockFile(), 50, () -> null));
            assertTrue(exception.getMessage().contains("Timed out"));
            return material.fingerprint();
        });

        assertFalse(fingerprint.isBlank());
    }

    private SharedSecurityPaths paths(boolean autoGenerate) {
        Jsons.SharedSecurityFields config = new Jsons.SharedSecurityFields();
        config.enabled = true;
        config.nodeId = "BackendA";
        config.directory = tempDirectory.toString();
        config.autoGenerateCertificate = autoGenerate;
        config.fsync = false;
        config.lockTimeoutMs = 10000;
        return SharedSecurityPaths.resolve(config, tempDirectory);
    }
}
