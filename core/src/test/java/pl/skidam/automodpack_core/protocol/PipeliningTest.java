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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * Pipelining over the HTTP contract: eight requests sit in flight on one connection and the responses complete strictly
 * in order; one failing response fails every pending request behind it (alignment is lost), and closing the connection
 * fails whatever is still pending.
 */
class PipeliningTest {
	private static final int AWAIT_SECONDS = 20;
	private static final int IN_FLIGHT = Connection.PIPELINE_DEPTH;

	@Test
	void eightInFlightRequestsAnswerInOrderOnOneConnection(@TempDir Path directory) throws Exception {
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

	/** A raw client connection to the contract server, so the ranged wire method is driven directly. */
	private static Connection connection(ConditionalFetchTest.ContractServer server, String secret) throws Exception {
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
		return new Connection(tls, plain, secret, hostHeader, DownloadClient.NET_EXECUTOR, () -> {});
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
