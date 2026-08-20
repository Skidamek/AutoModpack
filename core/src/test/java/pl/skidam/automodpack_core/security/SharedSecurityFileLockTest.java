package pl.skidam.automodpack_core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SharedSecurityFileLockTest {
    @TempDir
    Path tempDirectory;

    @Test
    void secondThreadGetsBoundedTimeoutInsteadOfOverlappingFileLock() throws Exception {
        Path lock = tempDirectory.resolve("host-secrets.lock");
        CountDownLatch entered = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                SharedSecurityFileLock.withExclusiveLock(lock, 5000, () -> {
                    entered.countDown();
                    Thread.sleep(500);
                    return null;
                });
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        holder.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Exception exception = assertThrows(Exception.class,
                () -> SharedSecurityFileLock.withExclusiveLock(lock, 50, () -> null));
        assertTrue(exception.getMessage().contains("Timed out"));
        holder.join(5000);
        assertFalse(holder.isAlive());
    }
}
