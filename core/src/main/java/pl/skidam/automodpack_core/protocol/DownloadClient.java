package pl.skidam.automodpack_core.protocol;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public abstract class DownloadClient implements AutoCloseable {

    protected DownloadClient() {
    }

    public static DownloadClient tryCreate(ModpackConnectionInfo connectionInfo, int poolSize) {
        if (connectionInfo == null || !connectionInfo.hasEndpointId()) {
            LOGGER.error("No iroh endpoint advertised by server");
            return null;
        }

        try {
            LOGGER.info("Trying iroh transport for {}", connectionInfo.minecraftServerAddress());
            return new IrohDownloadClient(connectionInfo, poolSize);
        } catch (Throwable e) {
            LOGGER.error("Failed to initialize iroh transport: {}", e.getMessage());
            LOGGER.debug("Iroh transport initialization failure", e);
            return null;
        }
    }

    public abstract CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback);

    public abstract CompletableFuture<Path> requestRefresh(byte[][] fileHashes, Path destination);

    @Override
    public abstract void close();
}
