package pl.skidam.automodpack_core.protocol;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

/**
 * The client transfer seam: one open transfer session to the pack's host, whatever serves it. The client never
 * distinguishes hostings, so every transport answers the same two requests - objects by sha1 with resume, documents by
 * reserved key with conditionals.
 */
public interface PackTransport extends AutoCloseable {

	/**
	 * Downloads one object into {@code destination}: when {@code offset} is positive the server may stream only the
	 * suffix, which is appended behind the stored prefix. On completion the destination holds the FULL object bytes
	 * whether the server resumed or restarted, and promotion judges the whole.
	 */
	CompletableFuture<Path> downloadFile(byte[] key, Path destination, long offset, IntConsumer progress);

	/** Document fetch (reserved keys); when {@code expectedSha1Hex} (lowercase hex) matches the served document the answer is {@code unchanged} and no body may follow. */
	CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, IntConsumer progress);

	/** Drops every in-flight transfer so a cancelled run cannot poison the next one. */
	void abortTransfers();

	@Override
	void close();
}
