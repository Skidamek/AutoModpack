package pl.skidam.automodpack_core.protocol;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

/**
 * The client transfer seam: one open transfer session to the pack's host, whatever serves it. The client never
 * distinguishes hostings, so every transport answers the same requests - whole objects by sha1, documents by reserved
 * key with conditionals, and the identity fetch for the waiting track. Each transport owns its wire window: resume,
 * chunk tiling, idle-lane stealing and pacing live behind {@link #downloadObject}, and on success the destination
 * holds the FULL object bytes while promotion judges the assembled whole.
 */
public interface PackTransport extends AutoCloseable {

	/** One complete object transfer: on success the destination holds the FULL object bytes; the transport owns resume, chunking, stealing and pacing. */
	CompletableFuture<Path> downloadObject(byte[] sha1Hex, Path destination, long fileSize, IntConsumer progress);

	/** The waiting-track fetch: single identity GET (no negotiation, no resume), aborted past maxBytes. */
	CompletableFuture<Path> downloadSmallObject(byte[] sha1Hex, Path destination, long maxBytes, OutputStream tap);

	/** Document fetch (reserved keys); when {@code expectedSha1Hex} (lowercase hex) matches the served document the answer is {@code unchanged} and no body may follow. */
	CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, IntConsumer progress);

	/**
	 * The same fetch with a tap: every served body byte is written to {@code tap} (decode-while-downloading) in
	 * addition to the destination. Transports without byte-level access drop the tap.
	 */
	CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, OutputStream tap);

	/** Whether one more transfer fits the transport's wire window; false means the caller should requeue and let the next settle re-run the dispatch. */
	boolean hasWireRoom();

	/** The one-line window receipt (window path, request duration estimate, per-lane settle rates) a run summary carries. */
	String windowSummary();

	/** Drops every in-flight transfer so a cancelled run cannot poison the next one. */
	void abortTransfers();

	@Override
	void close();
}
