package pl.skidam.automodpack_core.protocol.client;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

public interface Client extends AutoCloseable {
    CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback);
    CompletableFuture<Path> requestRefresh(byte[][] fileHashes, Path destination);
    void close();
}
