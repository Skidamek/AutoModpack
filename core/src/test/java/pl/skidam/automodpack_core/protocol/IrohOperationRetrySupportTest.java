package pl.skidam.automodpack_core.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IrohOperationRetrySupportTest {

    @Test
    void executeWithRetryRetriesTransientIrohFailures() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Path expected = Path.of("ok");

        CompletableFuture<Path> result = IrohOperationRetrySupport.executeWithRetry(
            "test iroh operation",
            () -> attempts.getAndIncrement() == 0
                ? CompletableFuture.failedFuture(new IOException("Failed while reading iroh stream"))
                : CompletableFuture.completedFuture(expected),
            ignored -> {}
        );

        assertEquals(expected, result.get(5, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
    }

    @Test
    void executeWithRetryDoesNotRetryNonRetryableFailures() {
        AtomicInteger attempts = new AtomicInteger();

        CompletableFuture<Path> result = IrohOperationRetrySupport.executeWithRetry(
            "test iroh operation",
            () -> {
                attempts.incrementAndGet();
                return CompletableFuture.failedFuture(new IOException("Some other i/o failure"));
            },
            ignored -> {}
        );

        ExecutionException error = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, error.getCause());
        assertEquals(1, attempts.get());
    }
}
