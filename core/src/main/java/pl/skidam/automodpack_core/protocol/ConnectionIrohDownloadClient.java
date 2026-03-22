package pl.skidam.automodpack_core.protocol;

import dev.iroh.IrohConnection;
import pl.skidam.automodpack_core.protocol.iroh.IrohTransportSupport;
import pl.skidam.automodpack_core.protocol.iroh.tunnel.ClientConnectionIrohTunnelSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public class ConnectionIrohDownloadClient extends AbstractIrohDownloadClient {
    private final ClientConnectionIrohTunnelSession session;
    private final ModpackConnectionInfo fallbackConnectionInfo;
    private final int poolSize;
    private final AtomicBoolean tunnelBroken = new AtomicBoolean(false);

    private volatile DownloadClient fallbackClient;

    public ConnectionIrohDownloadClient(
        ClientConnectionIrohTunnelSession session,
        ModpackConnectionInfo fallbackConnectionInfo,
        int poolSize
    ) {
        super(poolSize);
        this.session = session;
        this.fallbackConnectionInfo = fallbackConnectionInfo;
        this.poolSize = poolSize;
    }

    @Override
    public CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback) {
        return executeWithFallback(
            () -> super.downloadFile(fileHash, destination, chunkCallback),
            () -> requireFallbackClient().downloadFile(fileHash, destination, chunkCallback)
        );
    }

    @Override
    public CompletableFuture<Path> requestRefresh(byte[][] fileHashes, Path destination) {
        return executeWithFallback(
            () -> super.requestRefresh(fileHashes, destination),
            () -> requireFallbackClient().requestRefresh(fileHashes, destination)
        );
    }

    @Override
    protected IrohConnection requireConnection() throws IOException {
        return session.awaitConnection(IrohTransportSupport.SESSION_WAIT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        shutdownWorkers();
        DownloadClient activeFallback = fallbackClient;
        if (activeFallback != null) {
            activeFallback.close();
        }
    }

    private CompletableFuture<Path> executeWithFallback(Supplier<CompletableFuture<Path>> primary, Supplier<CompletableFuture<Path>> fallback) {
        return executeWithFallback(primary, fallback, 0);
    }

    private CompletableFuture<Path> executeWithFallback(Supplier<CompletableFuture<Path>> primary, Supplier<CompletableFuture<Path>> fallback, int attempt) {
        if (tunnelBroken.get()) {
            return fallback.get();
        }

        try {
            return primary.get().handle((result, error) -> {
                if (error == null) {
                    return CompletableFuture.completedFuture(result);
                }

                Throwable failure = IrohOperationRetrySupport.unwrap(error);
                if (!tunnelBroken.get() && IrohOperationRetrySupport.canRetry(failure, attempt)) {
                    LOGGER.warn(
                        "Connection-level iroh tunnel operation failed on attempt {}/{}, retrying in-session after {}ms: {}",
                        attempt + 1,
                        IrohOperationRetrySupport.MAX_IN_SESSION_RETRIES + 1,
                        IrohOperationRetrySupport.retryDelayMillis(attempt),
                        failure.getMessage()
                    );
                    IrohOperationRetrySupport.sleepBeforeRetry(attempt);
                    return executeWithFallback(primary, fallback, attempt + 1);
                }

                if (tunnelBroken.compareAndSet(false, true)) {
                    LOGGER.warn("Connection-level iroh tunnel failed, falling back to separate-socket transport: {}", failure.getMessage());
                    session.close();
                }
                return fallback.get();
            }).thenCompose(Function.identity());
        } catch (Throwable error) {
            Throwable failure = IrohOperationRetrySupport.unwrap(error);
            if (!tunnelBroken.get() && IrohOperationRetrySupport.canRetry(failure, attempt)) {
                LOGGER.warn(
                    "Connection-level iroh tunnel initialization failed on attempt {}/{}, retrying in-session after {}ms: {}",
                    attempt + 1,
                    IrohOperationRetrySupport.MAX_IN_SESSION_RETRIES + 1,
                    IrohOperationRetrySupport.retryDelayMillis(attempt),
                    failure.getMessage()
                );
                IrohOperationRetrySupport.sleepBeforeRetry(attempt);
                return executeWithFallback(primary, fallback, attempt + 1);
            }
            if (tunnelBroken.compareAndSet(false, true)) {
                LOGGER.warn("Connection-level iroh tunnel initialization failed, falling back to separate-socket transport: {}", failure.getMessage());
                session.close();
            }
            return fallback.get();
        }
    }

    private synchronized DownloadClient requireFallbackClient() {
        if (fallbackClient == null) {
            fallbackClient = DownloadClient.tryCreate(fallbackConnectionInfo, poolSize);
            if (fallbackClient == null) {
                throw new IllegalStateException("Failed to create fallback download client");
            }
        }
        return fallbackClient;
    }

}
