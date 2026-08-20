package pl.skidam.automodpack_core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SharedSecretsStore;
import pl.skidam.automodpack_core.config.Jsons;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SharedSecretsStoreTest {
    @TempDir
    Path tempDirectory;

    @Test
    void concurrentStoreInstancesMergeWithoutLosingEntries() throws Exception {
        SharedSecretsStore first = new SharedSecretsStore(paths("BackendA"));
        SharedSecretsStore second = new SharedSecretsStore(paths("BackendB"));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        int entriesPerNode = 75;

        for (int i = 0; i < entriesPerNode; i++) {
            String playerUuid = UUID.randomUUID().toString();
            futures.add(executor.submit(() -> save(first, playerUuid)));
            String secondPlayerUuid = UUID.randomUUID().toString();
            futures.add(executor.submit(() -> save(second, secondPlayerUuid)));
        }
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        String json = Files.readString(tempDirectory.resolve("host-secrets.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"formatVersion\""));
        assertTrue(json.contains("\"generation\""));
        assertTrue(json.contains("\"checksum\""));
        assertEquals(2 * entriesPerNode, new com.google.gson.JsonParser().parse(json).getAsJsonObject().getAsJsonObject("entries").size());
        assertFalse(json.contains("raw-secret"));
    }

    @Test
    void secretsFromDifferentIssuersCoexistAndReplacementIsScopedToIssuer() throws Exception {
        SharedSecretsStore firstNode = new SharedSecretsStore(paths("BackendA"));
        SharedSecretsStore secondNode = new SharedSecretsStore(paths("BackendB"));
        String playerUuid = UUID.randomUUID().toString();
        Secrets.Secret firstSecret = Secrets.generateSecret();
        Secrets.Secret secondSecret = Secrets.generateSecret();
        firstNode.save(playerUuid, firstSecret, 336);
        secondNode.save(playerUuid, secondSecret, 336);

        assertNotNull(firstNode.find(firstSecret.secret()));
        assertNotNull(secondNode.find(secondSecret.secret()));

        Secrets.Secret replacement = Secrets.generateSecret();
        firstNode.save(playerUuid, replacement, 336);
        assertNull(firstNode.find(firstSecret.secret()));
        assertNotNull(firstNode.find(replacement.secret()));
        assertNotNull(secondNode.find(secondSecret.secret()));
    }

    @Test
    void corruptPrimaryRecoversFromValidBackupAndWithoutBackupFailsClosed() throws Exception {
        SharedSecretsStore store = new SharedSecretsStore(paths("BackendA"));
        String firstUuid = UUID.randomUUID().toString();
        Secrets.Secret firstSecret = Secrets.generateSecret();
        store.save(firstUuid, firstSecret, 336);
        String secondUuid = UUID.randomUUID().toString();
        Secrets.Secret secondSecret = Secrets.generateSecret();
        store.save(secondUuid, secondSecret, 336);

        Path primary = tempDirectory.resolve("host-secrets.json");
        Files.writeString(primary, "{ this is corrupt", StandardCharsets.UTF_8);
        assertNotNull(store.find(firstSecret.secret()));
        assertNull(store.find(secondSecret.secret()));
        assertTrue(Files.readString(primary, StandardCharsets.UTF_8).contains("\"generation\""));

        Path noBackupDirectory = tempDirectory.resolve("no-backup");
        Files.createDirectories(noBackupDirectory);
        Jsons.SharedSecurityFields config = config("NoBackup", noBackupDirectory);
        config.backupCount = 0;
        SharedSecretsStore noBackup = new SharedSecretsStore(SharedSecurityPaths.resolve(config, noBackupDirectory));
        Path noBackupPrimary = noBackupDirectory.resolve("host-secrets.json");
        Files.writeString(noBackupPrimary, "not-json", StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> noBackup.find(Secrets.generateSecret().secret()));
        assertThrows(Exception.class, () -> noBackup.find(Secrets.generateSecret().secret()));
    }

    @Test
    void missingPrimaryRecoversFromNewestValidBackupInsteadOfCreatingEmptyStore() throws Exception {
        SharedSecretsStore store = new SharedSecretsStore(paths("BackendA"));
        String firstUuid = UUID.randomUUID().toString();
        Secrets.Secret firstSecret = Secrets.generateSecret();
        store.save(firstUuid, firstSecret, 336);

        store.save(UUID.randomUUID().toString(), Secrets.generateSecret(), 336);
        Files.delete(tempDirectory.resolve("host-secrets.json"));

        assertNotNull(store.find(firstSecret.secret()));
        assertTrue(Files.readString(tempDirectory.resolve("host-secrets.json"), StandardCharsets.UTF_8).contains("\"generation\""));
    }

    @Test
    void rawSecretFieldsAreRejectedEvenWhenTheDocumentIsOtherwiseReadable() throws Exception {
        SharedSecretsStore store = new SharedSecretsStore(paths("BackendA"));
        Secrets.Secret retainedSecret = Secrets.generateSecret();
        store.save(UUID.randomUUID().toString(), retainedSecret, 336);
        Secrets.Secret newerSecret = Secrets.generateSecret();
        store.save(UUID.randomUUID().toString(), newerSecret, 336);

        Path primary = tempDirectory.resolve("host-secrets.json");
        String json = Files.readString(primary, StandardCharsets.UTF_8)
                .replaceFirst("(\"entries\"\\s*:\\s*\\{)", "$1\"secret\":\"raw-secret-must-not-be-stored\", ");
        Files.writeString(primary, json, StandardCharsets.UTF_8);

        assertNotNull(store.find(retainedSecret.secret()));
        assertNull(store.find(newerSecret.secret()));
        assertFalse(Files.readString(primary, StandardCharsets.UTF_8).contains("raw-secret-must-not-be-stored"));
    }

    @Test
    void expiredSecretIsRemovedAndRejected() throws Exception {
        SharedSecretsStore store = new SharedSecretsStore(paths("BackendA"));
        Secrets.Secret expired = new Secrets.Secret(Secrets.generateSecret().secret(), (System.currentTimeMillis() / 1000L) - 10);
        store.save(UUID.randomUUID().toString(), expired, 0);
        assertNull(store.find(expired.secret()));
    }

    @Test
    void multipleJvmWorkersMergeIntoOneStore() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classPath = System.getProperty("java.class.path");
        List<Process> processes = new ArrayList<>();
        int processCount = 4;
        int entriesPerProcess = 20;
        for (int i = 0; i < processCount; i++) {
            Process process = new ProcessBuilder(
                    java,
                    "-cp", classPath,
                    SharedSecretsStoreProcessWorker.class.getName(),
                    tempDirectory.toString(),
                    "NODE" + i,
                    Integer.toString(entriesPerProcess)
            ).redirectErrorStream(true).start();
            processes.add(process);
        }
        for (Process process : processes) {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        }

        String json = Files.readString(tempDirectory.resolve("host-secrets.json"), StandardCharsets.UTF_8);
        assertEquals(processCount * entriesPerProcess,
                new com.google.gson.JsonParser().parse(json).getAsJsonObject().getAsJsonObject("entries").size());
    }

    private void save(SharedSecretsStore store, String playerUuid) {
        try {
            store.save(playerUuid, Secrets.generateSecret(), 336);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private SharedSecurityPaths paths(String nodeId) {
        return SharedSecurityPaths.resolve(config(nodeId, tempDirectory), tempDirectory);
    }

    private static Jsons.SharedSecurityFields config(String nodeId, Path directory) {
        Jsons.SharedSecurityFields config = new Jsons.SharedSecurityFields();
        config.enabled = true;
        config.nodeId = nodeId;
        config.directory = directory.toString();
        config.fsync = false;
        config.lockTimeoutMs = 5000;
        return config;
    }
}
