package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * Pipelining over the HTTP contract: a window of unsettled request bytes sits in flight on one connection and the
 * responses complete strictly in order; one failing response fails every pending request behind it (alignment is
 * lost), and closing the connection fails whatever is still pending.
 */
class PipeliningTest {
	private static final int AWAIT_SECONDS = 20;
	/** A full lane window of big takes: the count the byte window admits before the next one queues. */
	private static final int IN_FLIGHT = (int) (NetUtils.PIPELINE_WINDOW_BYTES / NetUtils.WIRE_CHUNK_BYTES);
	/** The same count, named for what it means at the connection's admission gate. */
	private static final int WINDOW_TAKES = IN_FLIGHT;

	@Test
	void aFullWindowOfInFlightRequestsAnswersInOrderOnOneConnection(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			List<String> hashes = storeObjects(server, IN_FLIGHT);
			server.expectPipeline(IN_FLIGHT);
			server.setResponseDelayMillis(50);
			try (DownloadClient client = client(server, "test-secret")) {
				List<CompletableFuture<Path>> futures = new ArrayList<>();
				List<Integer> completionOrder = new CopyOnWriteArrayList<>();
				for (int i = 0; i < IN_FLIGHT; i++) {
					final int index = i;
					CompletableFuture<Path> future = client.downloadSmallObject(hashes.get(i).getBytes(StandardCharsets.UTF_8), directory.resolve("object-" + i), -1L, null);
					future.whenComplete((path, error) -> completionOrder.add(index));
					futures.add(future);
				}

				CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				for (int i = 0; i < IN_FLIGHT; i++) assertArrayEquals(server.store().get(hashes.get(i)), Files.readAllBytes(directory.resolve("object-" + i)));
				assertEquals(1, server.connections.get(), "the whole pipeline rides one lane");
				assertTrue(server.pipelineArrived(), "all eight requests reached the socket before the first response left");
				assertEquals(expectedOrder(IN_FLIGHT), completionOrder, "responses complete strictly in request order");
			}
		}
	}

	@Test
	void aDeclaredLengthPastTheLimitFailsOnlyItsOwnRequest(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			List<String> hashes = storeObjects(server, 2);
			String sha1 = hashes.get(0);
			try (DownloadClient client = client(server, "test-secret")) {
				CompletableFuture<Path> limited = client.downloadSmallObject(sha1.getBytes(StandardCharsets.UTF_8), directory.resolve("limited"), 4L, null);
				ExecutionException failed = assertThrows(ExecutionException.class, () -> limited.get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertTrue(rootCause(failed).getMessage().contains("byte limit"), String.valueOf(rootCause(failed)));
				assertFalse(Files.exists(directory.resolve("limited")));

				// The body was discarded, not abandoned: the same lane answers the next request with its full bytes.
				Path whole = client.downloadSmallObject(sha1.getBytes(StandardCharsets.UTF_8), directory.resolve("whole"), -1L, null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertArrayEquals(server.store().get(sha1), Files.readAllBytes(whole));
				Path untouched = client.downloadSmallObject(hashes.get(1).getBytes(StandardCharsets.UTF_8), directory.resolve("untouched"), -1L, null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertArrayEquals(server.store().get(hashes.get(1)), Files.readAllBytes(untouched));
			}
		}
	}

	@Test
	void aFailingResponseFailsEveryPendingRequestBehindIt(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			List<String> hashes = storeObjects(server, IN_FLIGHT);
			String missing = hashes.set(IN_FLIGHT / 2, "ffffffffffffffffffffffffffffffffffffffff");
			server.store().remove(missing);
			server.expectPipeline(IN_FLIGHT);
			try (DownloadClient client = client(server, "test-secret")) {
				List<CompletableFuture<Path>> futures = new ArrayList<>();
				for (int i = 0; i < IN_FLIGHT; i++) futures.add(client.downloadSmallObject(hashes.get(i).getBytes(StandardCharsets.UTF_8), directory.resolve("object-" + i), -1L, null));

				// The responses stream in order; the 404 in the middle breaks the alignment, so everything queued behind it dies with it.
				for (int i = 0; i < IN_FLIGHT / 2; i++) futures.get(i).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				for (int i = IN_FLIGHT / 2; i < IN_FLIGHT; i++) {
					final int index = i;
					assertThrows(Exception.class, () -> futures.get(index).get(AWAIT_SECONDS, TimeUnit.SECONDS), "request " + i + " was queued behind the failing one");
				}
				assertEquals(IN_FLIGHT, server.requests.size());
			}
		}
	}

	@Test
	void closingTheConnectionFailsEveryPendingFuture(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			List<String> hashes = storeObjects(server, 4);
			server.setResponseDelayMillis(30_000);
			try (DownloadClient client = client(server, "test-secret")) {
				List<CompletableFuture<Path>> futures = new ArrayList<>();
				for (int i = 0; i < 4; i++) futures.add(client.downloadSmallObject(hashes.get(i).getBytes(StandardCharsets.UTF_8), directory.resolve("object-" + i), -1L, null));
				awaitArrivals(server, 4);

				client.abortTransfers();
				for (CompletableFuture<Path> future : futures) {
					var thrown = assertThrows(Exception.class, () -> future.get(AWAIT_SECONDS, TimeUnit.SECONDS));
					assertInstanceOf(IOException.class, rootCause(thrown));
				}
			}
		}
	}

	/**
	 * The streamer and the steals share one partial, so the offset-0 segment can land after a positioned one; its write
	 * must append at its own offset, never truncate the file - a truncate here silently wipes bytes another request
	 * already settled and the assembled object hash-mismatches.
	 */
	@Test
	void aLateZeroOffsetWriteKeepsEarlierPositionedBytes(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			byte[] object = new byte[256 * 1024];
			new SecureRandom().nextBytes(object);
			String sha1 = HashUtils.sha1(object);
			server.store().put(sha1, object);

			Path partial = directory.resolve("partial");
			try (Connection connection = connection(server, "test-secret")) {
				connection.sendDownloadFile(sha1.getBytes(StandardCharsets.UTF_8), Connection.ObjectTake.rangedSlice(partial, null, 100_000, object.length - 1, object.length)).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				connection.sendDownloadFile(sha1.getBytes(StandardCharsets.UTF_8), Connection.ObjectTake.rangedSlice(partial, null, 0, 99_999, object.length)).get(AWAIT_SECONDS, TimeUnit.SECONDS);
			}
			assertArrayEquals(object, Files.readAllBytes(partial));
		}
	}

	/**
	 * The window is bytes, not slots: a window's worth of 4 MiB takes fills one lane's whole budget and the next is
	 * rejected, while the same budget holds the small-take pipeline thousands of requests deep (pinned by the count
	 * tripwire test).
	 */
	@Test
	void theWindowAdmitsEveryBigTakeItHoldsAndRejectsTheNext(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			byte[] object = new byte[NetUtils.WIRE_CHUNK_BYTES];
			new SecureRandom().nextBytes(object);
			String sha1 = HashUtils.sha1(object);
			server.store().put(sha1, object);
			server.setResponseDelayMillis(30_000); // nothing settles: the wire shows the raw window
			try (Connection connection = connection(server, "test-secret")) {
				List<CompletableFuture<Path>> pending = new ArrayList<>();
				for (int i = 0; i < WINDOW_TAKES; i++) pending.add(takeWholeChunk(connection, sha1, directory.resolve("take-" + i)));
				for (CompletableFuture<Path> future : pending) assertFalse(future.isDone(), "every take inside the window must stay unsettled");
				assertRejected(connection, sha1, directory.resolve("take-" + WINDOW_TAKES), "window");
			}
		}
	}

	/** Open-ended takes (the range-ignoring-host degrade) debit one chunk flat, so the next one past the window is rejected. */
	@Test
	void openEndedTakesDebitAChunkEachAndTheOnePastTheWindowIsRejected(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			byte[] object = "a-small-object-behind-an-open-ended-take".getBytes(StandardCharsets.UTF_8);
			String sha1 = HashUtils.sha1(object);
			server.store().put(sha1, object);
			server.setResponseDelayMillis(30_000);
			try (Connection connection = connection(server, "test-secret")) {
				List<CompletableFuture<Path>> pending = new ArrayList<>();
				for (int i = 0; i < WINDOW_TAKES; i++) {
					pending.add(connection.sendDownloadFile(sha1.getBytes(StandardCharsets.UTF_8), Connection.ObjectTake.rangedSlice(directory.resolve("open-" + i), null, 0, -1, -1)));
				}
				for (CompletableFuture<Path> future : pending) assertFalse(future.isDone(), "every open-ended take inside the window must stay unsettled");
				var thrown = assertThrows(ExecutionException.class,
						() -> connection.sendDownloadFile(sha1.getBytes(StandardCharsets.UTF_8), Connection.ObjectTake.rangedSlice(directory.resolve("open-" + WINDOW_TAKES), null, 0, -1, -1)).get(AWAIT_SECONDS,
								TimeUnit.SECONDS));
				assertTrue(rootCause(thrown).getMessage().contains("window"), String.valueOf(rootCause(thrown)));
			}
		}
	}

	/** The count tripwire bounds bookkeeping past the byte window's reach: 5 KB takes charge 5 KB, and request 2049 is the one rejected. */
	@Test
	void theWindowAdmitsTwoThousandFortyEightSmallTakesThenTripsTheCountBound(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			byte[] object = new byte[5 * 1024];
			new SecureRandom().nextBytes(object);
			String sha1 = HashUtils.sha1(object);
			server.store().put(sha1, object);
			server.setResponseDelayMillis(30_000);
			try (Connection connection = connection(server, "test-secret")) {
				List<CompletableFuture<Path>> pending = new ArrayList<>();
				for (int i = 0; i < NetUtils.PIPELINE_MAX_REQUESTS; i++) {
					pending.add(connection.sendDownloadFile(sha1.getBytes(StandardCharsets.UTF_8), Connection.ObjectTake.rangedSlice(directory.resolve("small-" + i), null, 0, object.length - 1, object.length)));
				}
				for (CompletableFuture<Path> future : pending) assertFalse(future.isDone(), "every take inside the count bound must stay unsettled");
				var thrown = assertThrows(ExecutionException.class,
						() -> connection.sendDownloadFile(sha1.getBytes(StandardCharsets.UTF_8), Connection.ObjectTake.rangedSlice(directory.resolve("small-last"), null, 0, object.length - 1, object.length))
								.get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertTrue(rootCause(thrown).getMessage().contains("request"), String.valueOf(rootCause(thrown)));
			}
		}
	}

	/**
	 * The settle ordering pin: the settle releases its debit before the slot-free callback runs, so the request the
	 * callback submits inline sees the released bytes and lands on this lane instead of being rejected.
	 */
	@Test
	void aSettleReleasesTheDebitBeforeTheSlotFreeCallbackRuns(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			byte[] object = new byte[NetUtils.WIRE_CHUNK_BYTES];
			new SecureRandom().nextBytes(object);
			String sha1 = HashUtils.sha1(object);
			server.store().put(sha1, object);
			server.expectPipeline(16); // nothing answers until all sixteen take heads have arrived, so the window is exactly full
			AtomicBoolean callbackFired = new AtomicBoolean();
			AtomicBoolean inlineDispatchAdmitted = new AtomicBoolean();
			AtomicReference<CompletableFuture<Path>> inlineDispatch = new AtomicReference<>();
			AtomicReference<Connection> lane = new AtomicReference<>();
			try (Connection connection = connection(server, "test-secret", () -> {
				if (!callbackFired.compareAndSet(false, true)) return;
				// Runs on the reader thread between the settle's debit release and the next response: with the release
				// properly ordered the window reads 60 MiB and the 4 MiB debit fits; with the old ordering it would
				// still read 64 MiB and this submit would be rejected.
				CompletableFuture<Path> future = takeWholeChunk(lane.get(), sha1, directory.resolve("inline-dispatch"));
				inlineDispatch.set(future);
				inlineDispatchAdmitted.set(!future.isCompletedExceptionally());
			})) {
				lane.set(connection);
				for (int i = 0; i < 16; i++) takeWholeChunk(connection, sha1, directory.resolve("take-" + i)).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertTrue(callbackFired.get(), "the first settle must free a slot");
				assertTrue(inlineDispatchAdmitted.get(), "the settle's debit release must precede the slot-free callback");
				assertArrayEquals(object, Files.readAllBytes(inlineDispatch.get().get(AWAIT_SECONDS, TimeUnit.SECONDS)));
			}
		}
	}

	/** One full-chunk ranged take: the largest debit a take can carry against the window. */
	private static CompletableFuture<Path> takeWholeChunk(Connection connection, String sha1, Path destination) {
		return connection.sendDownloadFile(sha1.getBytes(StandardCharsets.UTF_8), Connection.ObjectTake.rangedSlice(destination, null, 0, NetUtils.WIRE_CHUNK_BYTES - 1L, NetUtils.WIRE_CHUNK_BYTES));
	}

	/** Submits one take past a full window and fails unless the rejection names the window. */
	private static void assertRejected(Connection connection, String sha1, Path destination, String expectedReason) throws Exception {
		CompletableFuture<Path> future = takeWholeChunk(connection, sha1, destination);
		var thrown = assertThrows(ExecutionException.class, () -> future.get(AWAIT_SECONDS, TimeUnit.SECONDS));
		assertTrue(rootCause(thrown).getMessage().contains(expectedReason), String.valueOf(rootCause(thrown)));
	}

	/** A raw client connection to the contract server, so the ranged wire method is driven directly. */
	private static Connection connection(ConditionalFetchTest.ContractServer server, String secret) throws Exception {
		return connection(server, secret, () -> {});
	}

	private static Connection connection(ConditionalFetchTest.ContractServer server, String secret, Runnable onSlotFreed) throws Exception {
		String hostHeader = "127.0.0.1:" + server.port();
		Socket plain = new Socket(InetAddress.getLoopbackAddress(), server.port());
		plain.setSoTimeout(NetUtils.NETWORK_TIMEOUT_MILLIS);
		DataOutputStream out = new DataOutputStream(new BufferedOutputStream(plain.getOutputStream()));
		DataInputStream in = new DataInputStream(new BufferedInputStream(plain.getInputStream()));
		byte[] hostBytes = hostHeader.getBytes(StandardCharsets.UTF_8);
		out.writeInt(NetUtils.MAGIC_AMMH);
		out.writeShort(hostBytes.length);
		out.write(hostBytes);
		out.flush();
		if (in.readInt() != NetUtils.MAGIC_AMOK) throw new IOException("Invalid response from server");
		SSLSocket tls = (SSLSocket) trustAllContext().getSocketFactory().createSocket(plain, "127.0.0.1", server.port(), true);
		tls.setEnabledProtocols(new String[]{"TLSv1.3"});
		tls.startHandshake();
		tls.setSoTimeout(0);
		return new Connection(tls, plain, secret, hostHeader, DownloadClient.NET_EXECUTOR, onSlotFreed);
	}

	private static SSLContext trustAllContext() throws Exception {
		SSLContext context = SSLContext.getInstance("TLSv1.3");
		context.init(null, new TrustManager[]{new X509TrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) {}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) {}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		}}, null);
		return context;
	}

	private static List<String> storeObjects(ConditionalFetchTest.ContractServer server, int count) {
		List<String> hashes = new ArrayList<>();
		byte[] seed = new byte[16];
		for (int i = 0; i < count; i++) {
			new SecureRandom().nextBytes(seed);
			byte[] object = ("pipelined-object-" + new BigInteger(1, seed).toString(16)).getBytes(StandardCharsets.UTF_8);
			String sha1 = HashUtils.sha1(object);
			server.store().put(sha1, object);
			hashes.add(sha1);
		}
		return hashes;
	}

	private static List<Integer> expectedOrder(int count) {
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < count; i++) order.add(i);
		return order;
	}

	private static void awaitArrivals(ConditionalFetchTest.ContractServer server, int count) throws InterruptedException {
		long deadline = System.currentTimeMillis() + AWAIT_SECONDS * 1000L;
		while (server.requests.size() < count && System.currentTimeMillis() < deadline) Thread.sleep(10);
		assertTrue(server.requests.size() >= count, "expected " + count + " requests at the server, saw " + server.requests.size());
	}

	private static Throwable rootCause(Throwable thrown) {
		Throwable cause = thrown;
		while (cause.getCause() != null)
			cause = cause.getCause();
		return cause;
	}

	private static DownloadClient client(ConditionalFetchTest.ContractServer server, String secret) throws Exception {
		ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
				new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.MAGIC, server.fingerprint(), null);
		return DownloadClient.createAsync(connectionInfo, secret, ignored -> CompletableFuture.completedFuture(false)).get(AWAIT_SECONDS, TimeUnit.SECONDS);
	}
}
