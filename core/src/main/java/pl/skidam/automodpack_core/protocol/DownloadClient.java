package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.protocol.NetUtils.WIRE_CHUNK_BYTES;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.IntConsumer;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.protocol.Connection.ObjectTake;
import pl.skidam.automodpack_core.protocol.LaneConnector.Route;
import pl.skidam.automodpack_core.utils.ByteFormat;

/**
 * The client side of the host wire: one endpoint's identity, its pooled lanes ({@link LanePool}) and the object transfers
 * that run over them ({@link ObjectTransfer}). The executors live here because the platform metadata fetcher shares this
 * client's network executor; everything about one endpoint's behaviour belongs to the two collaborators.
 */
public class DownloadClient implements PackTransport, HostTiles {

	/** The transport's own async callbacks (connection IO, manifest and platform fetches); app work belongs to the app's executor. */
	public static final ExecutorService NET_EXECUTOR = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "automodpack-net");
		t.setDaemon(true);
		return t;
	});

	/** One daemon clock for delayed work (a throttled host's Retry-After, a refused endpoint's lane-open cooldown re-pump); the wait never parks a lane's reader. */
	static final ScheduledExecutorService RETRY_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "automodpack-retry-clock");
		t.setDaemon(true);
		return t;
	});

	// The pooled pipeline lanes. They buy exactly two physical things: loss-regime multiplication (a lost segment stalls
	// one lane's pipeline, not the transfer) and streaming through a lane re-handshake; on a clean link a single
	// connection is already bandwidth-equivalent. Measured on the bench fixture (101 files, ~250 MiB): clean and
	// 0.1%-loss runs are indistinguishable across {1,3,5} lanes, while at 1% loss + 100 ms delay the same transfer
	// takes 230 s at 5 lanes, 442 s at 3, and 910 s at 1 - the stall regime scales near-linearly with lanes, so the
	// count stays 5. Kept split from the platform-worker count it used to be welded to.
	public static final int LANES = 5;

	// The lane-open backoff after failed connects. Receipt: a refused connect fails in well under a millisecond, so the
	// 1 s base sits >1000x past the failure feedback latency and inside the codebase's retry-spacing band (the 1.5 s
	// DNS re-resolution in LaneConnector.resolveRouteAsync, the 1-2 s throttle default in ObjectTransfer.retryDelayMillis);
	// the 30 s cap is half the 90 s TRANSFER_WRITE_STALL_TIMEOUT, so a backed-off pool can never be mistaken for a wedged
	// transfer, and 2x the 15 s NETWORK_TIMEOUT.
	static final long LANE_OPEN_COOLDOWN_BASE_MILLIS = 1000;
	static final long LANE_OPEN_COOLDOWN_CAP_MILLIS = 30_000;

	final TransportSession session;
	private final LanePool lanes;

	private DownloadClient(ConnectionJsons.ConnectionInfo connectionInfo, String secret, Function<X509Certificate, CompletableFuture<Boolean>> trustCallback, Route route) {
		this.session = new TransportSession(connectionInfo.endpoint.getHostString());
		this.lanes = new LanePool(session, new LaneConnector(connectionInfo, trustCallback, route), secret, connectionInfo.endpoint.getHostString() + ":" + connectionInfo.endpoint.getPort());
	}

	public static CompletableFuture<DownloadClient> createAsync(ConnectionJsons.ConnectionInfo connectionInfo, String secret,
			Function<X509Certificate, CompletableFuture<Boolean>> trustCallback) {
		if (connectionInfo == null || !connectionInfo.isComplete())
			return CompletableFuture.failedFuture(new IllegalArgumentException("Connection origin or endpoint is missing"));

		return LaneConnector.resolveRouteAsync(connectionInfo).thenCompose(route -> {
			DownloadClient client = new DownloadClient(connectionInfo, secret, trustCallback, route);
			return client.lanes.openLane().thenApply(ignored -> client);
		}).whenComplete((client, error) -> {
			if (error != null && client != null) client.close();
		});
	}

	/**
	 * One complete object transfer: {@code destination} is the slice directory {@code staging/<sha1>/}. On success
	 * every tile is present; the caller concatenates and hashes. Resume is the directory listing.
	 */
	@Override
	public CompletableFuture<Path> downloadObject(byte[] sha1Hex, Path destination, long fileSize, IntConsumer progress) {
		try {
			Files.createDirectories(destination);
			if (fileSize == 0 || PartialResume.complete(destination, fileSize)) return CompletableFuture.completedFuture(destination);
			return new ObjectTransfer(sha1Hex, destination, fileSize, progress, lanes, lanes::epoch, session).start();
		} catch (IOException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** The waiting-track fetch: one identity whole-object take with no negotiation and no resume, aborted past maxBytes. */
	@Override
	public CompletableFuture<Path> downloadSmallObject(byte[] sha1Hex, Path destination, long maxBytes, OutputStream tap) {
		return lanes.withSlot(0, WIRE_CHUNK_BYTES, connection -> connection.sendDownloadFile(sha1Hex, ObjectTake.wholeObject(destination, tap, maxBytes)));
	}

	@Override
	public CompletableFuture<Path> downloadTile(byte[] sha1Hex, Path sliceDirectory, long fileSize, long tileStart, IntConsumer progress) {
		long length = PartialResume.sliceLength(tileStart, fileSize);
		if (length <= 0) return CompletableFuture.completedFuture(sliceDirectory);
		if (!Files.isDirectory(sliceDirectory)) return CompletableFuture.failedFuture(new IOException("The object finished before the host tile could be taken"));
		// The take is never cancelled by the caller: it is a measurement, and it finishes on its own or fails on its own. It
		// never recreates a staging directory the promotion already removed.
		return lanes
				.withSlot(0, length,
						connection -> connection.sendDownloadFile(sha1Hex, ObjectTake.rangedSlice(PartialResume.sliceHostTemp(sliceDirectory, tileStart), progress, tileStart, tileStart + length - 1, fileSize, 0)))
				.thenApply(directory -> {
					try {
						PartialResume.promoteSlice(sliceDirectory, tileStart, fileSize);
					} catch (IOException e) {
						throw new CompletionException(e);
					}
					return directory;
				});
	}

	/** Document fetch (reserved keys) under the conditional, null for unconditional: a matching validator answers 304 and {@code destination} is not written. */
	@Override
	public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, DocumentConditional conditional, IntConsumer chunkCallback) {
		return lanes.withSlot(0, WIRE_CHUNK_BYTES, connection -> connection.sendDownloadDocument(key, destination, conditional, chunkCallback, null));
	}

	/** The same fetch with a tap: served body bytes reach the tap (decode-while-downloading) and the destination alike. */
	@Override
	public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, DocumentConditional conditional, OutputStream tap) {
		return lanes.withSlot(0, WIRE_CHUNK_BYTES, connection -> connection.sendDownloadDocument(key, destination, conditional, null, tap));
	}

	/** Drops every pooled and in-flight transfer connection so a cancelled run cannot poison the next one. */
	@Override
	public void abortTransfers() {
		lanes.abortTransfers();
	}

	/** The one-line receipt a run summary carries: takes sent, retries and bytes arrived, over the lanes the run actually opened. */
	@Override
	public String windowSummary() {
		int lanes = session.lanesHighWater.get();
		return session.takesSubmitted.get() + " takes (" + session.takeRetries.get() + " retried), " + ByteFormat.formatSize(session.bytesDownloaded.get()) + " over " + lanes + (lanes == 1 ? " lane" : " lanes");
	}

	@Override
	public void close() {
		lanes.close();
	}
}
