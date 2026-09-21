package pl.skidam.automodpack_core.protocol;

import java.io.OutputStream;
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
	 * Downloads the object bytes [{@code offset}, {@code endInclusive}] into {@code destination} at those absolute
	 * offsets; {@code endInclusive < 0} means through end of file. Bytes land behind any already-stored prefix and
	 * promotion judges the assembled whole.
	 */
	CompletableFuture<Path> downloadFile(byte[] key, Path destination, long offset, long endInclusive, IntConsumer progress, int lane);

	/**
	 * Downloads one object into {@code destination} from {@code offset} through end of file: when {@code offset} is
	 * positive the server may stream only the suffix, whose bytes land at their absolute offsets behind the
	 * already-stored prefix. On completion the destination holds the FULL object bytes whether the server resumed or
	 * restarted, and promotion judges the whole.
	 */
	default CompletableFuture<Path> downloadFile(byte[] key, Path destination, long offset, IntConsumer progress) {
		return downloadFile(key, destination, offset, -1L, progress, 0);
	}

	/**
	 * The same fetch with a preferred pipeline lane: concurrent transfers land on distinct lanes while small items fill
	 * each lane's depth. Transports without a lane pool ignore the hint.
	 */
	default CompletableFuture<Path> downloadFile(byte[] key, Path destination, long offset, IntConsumer progress, int lane) {
		return downloadFile(key, destination, offset, -1L, progress, lane);
	}

	/**
	 * The same fetch with a tap and a negotiation choice: every served body byte is written to {@code tap}
	 * (decode-while-downloading) in addition to the destination, {@code offerEncoding} sends Accept-Encoding
	 * (true) or asks for identity (false), and {@code limitBytes} rejects an object whose declared length exceeds
	 * it before a body byte is read (negative means no limit). Transports without byte-level access drop the tap.
	 */
	default CompletableFuture<Path> downloadFile(byte[] key, Path destination, long offset, long endInclusive, IntConsumer progress, OutputStream tap, boolean offerEncoding, long limitBytes, int lane) {
		return downloadFile(key, destination, offset, endInclusive, progress, lane);
	}

	/** Document fetch (reserved keys); when {@code expectedSha1Hex} (lowercase hex) matches the served document the answer is {@code unchanged} and no body may follow. */
	CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, IntConsumer progress);

	/**
	 * The same fetch with a tap: every served body byte is written to {@code tap} (decode-while-downloading) in
	 * addition to the destination. Transports without byte-level access drop the tap.
	 */
	default CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, OutputStream tap) {
		return downloadDocument(key, destination, expectedSha1Hex, (IntConsumer) null);
	}

	/** Drops every in-flight transfer so a cancelled run cannot poison the next one. */
	void abortTransfers();

	/** How many unsettled requests the pipelined wire can hold across all lanes; 0 means the transport paces itself. */
	default int pipelineCapacity() {
		return 0;
	}

	@Override
	void close();
}
