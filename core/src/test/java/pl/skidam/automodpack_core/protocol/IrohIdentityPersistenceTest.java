package pl.skidam.automodpack_core.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.skidam.automodpack_core.protocol.iroh.IrohIdentity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IrohIdentityPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void testLoadOrCreateSecretCreatesAndReusesSameKey() throws Exception {
        Path keyFile = tempDir.resolve("iroh.key");

        byte[] created = IrohIdentity.loadOrCreateSecret(keyFile);
        byte[] reused = IrohIdentity.loadOrCreateSecret(keyFile);

        assertTrue(Files.exists(keyFile), "Shared iroh key should be created when missing");
        assertEquals(32L, Files.size(keyFile), "Shared iroh key should always be 32 bytes");
        assertArrayEquals(created, reused, "Existing shared iroh key should be reused unchanged");
        assertArrayEquals(created, IrohIdentity.loadSecret(keyFile), "Explicit load should match the persisted key");
    }

    @Test
    void testLoadSecretRejectsInvalidLength() throws Exception {
        Path keyFile = tempDir.resolve("iroh.key");
        Files.write(keyFile, new byte[] { 1, 2, 3 });

        IOException error = assertThrows(IOException.class, () -> IrohIdentity.loadSecret(keyFile));
        assertTrue(error.getMessage().contains("Invalid iroh secret key length"), "Error should explain the invalid key length");
        assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(keyFile), "Invalid key file should remain untouched");
    }
}
