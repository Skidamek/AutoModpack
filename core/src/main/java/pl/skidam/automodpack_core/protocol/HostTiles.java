package pl.skidam.automodpack_core.protocol;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

/**
 * One tile of an object into its staging directory, named only once the tile is whole. The host wire is the only
 * transport that can do this: it is how the host measures itself without writing the same file the platform is already
 * sending, and how a far-end tile of that object is filled from the other side.
 */
public interface HostTiles {

	CompletableFuture<Path> downloadTile(byte[] sha1Hex, Path sliceDirectory, long fileSize, long tileStart, IntConsumer progress);
}
