package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import static pl.skidam.automodpack_core.Constants.LOGGER;

final class IrohOperationRetrySupport {
    static final int MAX_IN_SESSION_RETRIES = 5;
    private static final long INITIAL_IN_SESSION_RETRY_DELAY_MILLIS = 1_000L;
    private static final long MAX_IN_SESSION_RETRY_DELAY_MILLIS = 10_000L;

    private IrohOperationRetrySupport() {
    }

    static <T> CompletableFuture<T> executeWithRetry(String operationDescription, Supplier<CompletableFuture<T>> operation) {
        return executeWithRetry(operationDescription, operation, IrohOperationRetrySupport::sleepBeforeRetry, 0);
    }

    static <T> CompletableFuture<T> executeWithRetry(
        String operationDescription,
        Supplier<CompletableFuture<T>> operation,
        IntConsumer retrySleeper
    ) {
        return executeWithRetry(operationDescription, operation, retrySleeper, 0);
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
            && (current instanceof CompletionException || current instanceof ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    static boolean canRetry(Throwable error, int attempt) {
        return attempt < MAX_IN_SESSION_RETRIES && isRetryableFailure(error);
    }

    static long retryDelayMillis(int attempt) {
        long delay = INITIAL_IN_SESSION_RETRY_DELAY_MILLIS << Math.max(0, attempt);
        return Math.min(delay, MAX_IN_SESSION_RETRY_DELAY_MILLIS);
    }

    static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(retryDelayMillis(attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isRetryableFailure(Throwable error) {
        if (!(error instanceof IOException)) {
            return false;
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("Failed while reading iroh stream")
            || message.contains("Timed out while reading iroh stream")
            || message.contains("Failed to open iroh stream")
            || message.contains("Timed out while opening iroh stream")
            || message.contains("Failed to write iroh request")
            || message.contains("Failed to finish iroh request stream");
    }

    private static <T> CompletableFuture<T> executeWithRetry(
        String operationDescription,
        Supplier<CompletableFuture<T>> operation,
        IntConsumer retrySleeper,
        int attempt
    ) {
        try {
            return operation.get().handle((result, error) -> {
                if (error == null) {
                    return CompletableFuture.completedFuture(result);
                }

                Throwable failure = unwrap(error);
                if (canRetry(failure, attempt)) {
                    LOGGER.warn(
                        "{} failed on attempt {}/{}, retrying in-session after {}ms: {}",
                        operationDescription,
                        attempt + 1,
                        MAX_IN_SESSION_RETRIES + 1,
                        retryDelayMillis(attempt),
                        failure.getMessage()
                    );
                    retrySleeper.accept(attempt);
                    return executeWithRetry(operationDescription, operation, retrySleeper, attempt + 1);
                }

                return CompletableFuture.<T>failedFuture(failure);
            }).thenCompose(Function.identity());
        } catch (Throwable error) {
            Throwable failure = unwrap(error);
            if (canRetry(failure, attempt)) {
                LOGGER.warn(
                    "{} initialization failed on attempt {}/{}, retrying in-session after {}ms: {}",
                    operationDescription,
                    attempt + 1,
                    MAX_IN_SESSION_RETRIES + 1,
                    retryDelayMillis(attempt),
                    failure.getMessage()
                );
                retrySleeper.accept(attempt);
                return executeWithRetry(operationDescription, operation, retrySleeper, attempt + 1);
            }
            return CompletableFuture.failedFuture(failure);
        }
    }
}
