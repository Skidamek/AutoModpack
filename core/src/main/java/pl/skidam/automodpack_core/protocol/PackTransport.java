package pl.skidam.automodpack_core.protocol;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

/**
 * The client transfer seam: one open transfer session to the pack's host, whatever serves it. The client never
 * distinguishes hostings, so every transport answers the same requests - whole objects by sha1, documents by reserved
 * key with conditionals, and the identity fetch for the waiting track. Each transport owns its transfer tiling: resume,
 * chunk tiling and idle-lane stealing live behind {@link #downloadObject}, and on success the destination holds the
 * FULL object bytes while promotion judges the assembled whole.
 */
public interface PackTransport extends AutoCloseable {

	/** One complete object transfer: on success the destination holds the FULL object bytes; the transport owns resume, chunking and stealing. */
	CompletableFuture<Path> downloadObject(byte[] sha1Hex, Path destination, long fileSize, IntConsumer progress);

	/** The waiting-track fetch: single identity GET (no negotiation, no resume), aborted past maxBytes. */
	CompletableFuture<Path> downloadSmallObject(byte[] sha1Hex, Path destination, long maxBytes, OutputStream tap);

	/**
	 * Document fetch (reserved keys) under the given conditional, null for unconditional: the 200 body hash against
	 * the conditional's vouched sha1 is the ground truth, and a matching validator answers {@code unchanged} with no
	 * body.
	 */
	CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, DocumentConditional conditional, IntConsumer progress);

	/**
	 * The same fetch with a tap: every served body byte is written to {@code tap} (decode-while-downloading) in
	 * addition to the destination. Transports without byte-level access drop the tap.
	 */
	CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, DocumentConditional conditional, OutputStream tap);

	/**
	 * The validators one conditional document fetch sends. {@code sha1Hex} is the vouched document hash: the wire
	 * sends it quoted, and the 200 body hash against it decides whether a host that ignored the condition served
	 * unchanged bytes. {@code hostEtag} is the host's own cached ETag header value (raw, quotes included), sent
	 * behind the sha1 so a foreign host can match it and answer 304; it is never sent alone, because a 304 must
	 * always mean "the vouched mirror is current" - without the vouch there are no verified bytes for a 304 to
	 * stand behind, so such a fetch proceeds unconditionally instead.
	 */
	record DocumentConditional(String sha1Hex, String hostEtag) {
		/** The If-None-Match header value: one quoted sha1, or a comma-separated list carrying the host's own etag too. */
		public String ifNoneMatchValue() {
			if (sha1Hex == null) return null;
			return hostEtag == null ? '"' + sha1Hex + '"' : '"' + sha1Hex + "\", " + hostEtag;
		}
	}

	/** The one-line transfer receipt (takes, retries, bytes over the lanes) a run summary carries. */
	String windowSummary();

	/** Drops every in-flight transfer so a cancelled run cannot poison the next one. */
	void abortTransfers();

	@Override
	void close();
}
