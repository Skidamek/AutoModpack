package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The whole-object transfer contract: exact tiling across chunk edges (every byte requested exactly once), resume
 * behind the stored prefix, and the complete-size shortcut that never touches the wire.
 */
class DownloadObjectTest {
	/** Generous bound for loopback handshakes that complete in milliseconds when warm; cold CI runners have blown past five seconds here. */
	private static final int AWAIT_SECONDS = 20;

	/**
	 * The transfer's chunked takes must tile any size exactly: a whole-chunk tail walk past a non-chunk-multiple size
	 * once orphaned the bytes between the first chunk and the aligned tail, so no request ever covered them and the
	 * transfer never finished.
	 */
	@Test
	void transfersTileNonChunkMultipleSizesExactly(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			byte[] object = new byte[NetUtils.WIRE_CHUNK_BYTES * 2 + 1234];
			new SecureRandom().nextBytes(object);
			String sha1 = HashUtils.sha1(object);
			server.store().put(sha1, object);
			try (DownloadClient client = client(server, "test-secret")) {
				Path destination = directory.resolve("object");
				client.downloadObject(sha1.getBytes(StandardCharsets.UTF_8), destination, object.length, null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertArrayEquals(object, Files.readAllBytes(destination));
				assertTrue(client.windowSummary().contains("3 takes (0 retried)"), "the honest receipt counts the three takes: " + client.windowSummary());
			}
			long next = 0;
			for (long[] range : server.ranges.stream().sorted(Comparator.comparingLong(range -> range[0])).toList()) {
				assertEquals(next, range[0], "a take must start exactly where the coverage ends");
				next = range[1] + 1;
			}
			assertEquals(object.length, next, "the takes must cover every byte of the object exactly once");
		}
	}

	@Test
	void resumeStartsBehindTheStoredPrefixAndCompleteSizesSkipTheWire(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			byte[] object = new byte[256 * 1024];
			new SecureRandom().nextBytes(object);
			String sha1 = HashUtils.sha1(object);
			server.store().put(sha1, object);
			try (DownloadClient client = client(server, "test-secret")) {
				Path destination = directory.resolve("object");
				Files.write(destination, Arrays.copyOf(object, 100_000));
				client.downloadObject(sha1.getBytes(StandardCharsets.UTF_8), destination, object.length, null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertArrayEquals(object, Files.readAllBytes(destination));
				assertEquals(1, server.ranges.size());
				assertEquals(100_000, server.ranges.get(0)[0], "the resumed transfer requests exactly behind the stored prefix");

				// A destination already at the advertised size skips the network entirely.
				server.requests.clear();
				assertEquals(destination, client.downloadObject(sha1.getBytes(StandardCharsets.UTF_8), destination, object.length, null).get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertEquals(0, server.requests.size(), "a complete-sized destination must not touch the wire");
			}
		}
	}

	/**
	 * The one physical flow-control bound: a transfer past the pool's window fills exactly the pool's unsettled-take
	 * bound and queues the rest of its tiles behind unsettled takes - nothing fails window-full, nothing stalls.
	 */
	@Test
	void aTransferPastThePoolWindowKeepsAtMostThePoolBoundUnsettled(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			// The hash is unstored and every response is delayed far past the test: nothing settles, so the wire shows the raw bound.
			byte[] sha1 = HashUtils.sha1("an-unstored-oversized-object".getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
			int poolBound = (int) (DownloadClient.LANES * (NetUtils.PIPELINE_WINDOW_BYTES / NetUtils.WIRE_CHUNK_BYTES));
			long fileSize = (long) NetUtils.WIRE_CHUNK_BYTES * poolBound + 1; // tiles into poolBound + 1 takes, one past the bound
			server.setResponseDelayMillis(30_000);
			try (DownloadClient client = client(server, "test-secret")) {
				CompletableFuture<Path> transfer = client.downloadObject(sha1, directory.resolve("object"), fileSize, null);
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
				while (server.requests.size() < poolBound && System.nanoTime() < deadline) Thread.sleep(50);
				assertEquals(poolBound, server.requests.size(), "the transfer must fill exactly the pool's unsettled-take bound, requests: " + server.requests.size());
				Thread.sleep(1_000); // the quiet period: with nothing settling, not one more take may hit the wire
				assertEquals(poolBound, server.requests.size(), "takes past the bound must queue unsettled, requests: " + server.requests.size());
				client.abortTransfers();
				assertThrows(ExecutionException.class, () -> transfer.get(5, TimeUnit.SECONDS), "an aborted transfer must fail, never hang");
			}
		}
	}

	/** A 404 is a pack-hygiene verdict, not congestion: the failing range is never retried. */
	@Test
	void aMissingObjectFailsTheTransferWithoutRetryingARange(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			// The hash is well-formed but the server stores nothing under it: every take is answered 404.
			byte[] sha1 = HashUtils.sha1("a-missing-object".getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
			long fileSize = NetUtils.WIRE_CHUNK_BYTES * 2L + 1234; // tiles into the head plus two tails: three takes
			server.expectPipeline(3); // all three takes must reach the socket before any answer, so the request count is exact
			try (DownloadClient client = client(server, "test-secret")) {
				var thrown = assertThrows(ExecutionException.class, () -> client.downloadObject(sha1, directory.resolve("object"), fileSize, null).get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertInstanceOf(MissingObjectException.class, rootCause(thrown));
				assertTrue(server.pipelineArrived(), "all three takes reached the socket before the first answer");
			}
			assertEquals(3, server.requests.size(), "each take asked exactly once - a 404 range is never retried");
		}
	}

	/** A dropped lane is transient: the failed ranges retry in place and the transfer still promotes. */
	@Test
	void aDroppedRangeRetriesInPlaceAndStillPromotes(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			byte[] object = new byte[NetUtils.WIRE_CHUNK_BYTES * 2 + 1234];
			new SecureRandom().nextBytes(object);
			String sha1 = HashUtils.sha1(object);
			server.store().put(sha1, object);
			server.dropNextObjectRequest();
			try (DownloadClient client = client(server, "test-secret")) {
				Path destination = directory.resolve("object");
				client.downloadObject(sha1.getBytes(StandardCharsets.UTF_8), destination, object.length, null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertArrayEquals(object, Files.readAllBytes(destination));
				assertTrue(server.requests.size() > 3, "the dropped take must have been retried, requests: " + server.requests.size());
				// The drop closes the whole lane, so every take pipelined behind it retries together; the exact count is
				// the scheduler's business. The receipt must count them honestly: at least one retry, every take booked.
				assertTrue(client.windowSummary().matches("\\d+ takes \\([1-9]\\d* retried\\), [\\d.]+ \\S+ over 5 lanes"), "the honest receipt counts the lane's retried takes: " + client.windowSummary());
			}
		}
	}

	/** A range-ignoring host fails the first bounded take fast with a capability verdict, and the requeued task rides one open-ended take to a complete download. */
	@Test
	void aRangeIgnoringHostFailsFastThenDownloadsInOneOpenEndedTake(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			byte[] object = new byte[NetUtils.WIRE_CHUNK_BYTES * 2 + 1234];
			new SecureRandom().nextBytes(object);
			String sha1 = HashUtils.sha1(object);
			server.store().put(sha1, object);
			server.ignoreRanges.set(true);
			try (DownloadClient client = client(server, "test-secret")) {
				Path destination = directory.resolve("object");
				var thrown = assertThrows(ExecutionException.class, () -> client.downloadObject(sha1.getBytes(StandardCharsets.UTF_8), destination, object.length, null).get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertInstanceOf(RangeIgnoredException.class, rootCause(thrown));
				assertTrue(client.rangeIgnoredHost, "the capability flag is set for the whole client");
				assertFalse(Files.exists(destination), "nothing was written before the verdict");

				// The manager requeues the task; under the flag it skips tiling and rides one open-ended take.
				assertEquals(destination, client.downloadObject(sha1.getBytes(StandardCharsets.UTF_8), destination, object.length, null).get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertArrayEquals(object, Files.readAllBytes(destination));
			}
			assertTrue(server.ranges.isEmpty(), "every take was answered by the barebones head rules, never a 206");
		}
	}

	/** Against a range-ignoring host a whole-file take is its own slice: the bounded 200 whose declared length equals the slice is the whole file, so the object promotes without ever tiling. */
	@Test
	void aWholeFileTakeAcceptsARangeIgnoringHostsBounded200(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			byte[] object = "a-small-object-under-one-chunk".getBytes(StandardCharsets.UTF_8);
			String sha1 = HashUtils.sha1(object);
			server.store().put(sha1, object);
			server.ignoreRanges.set(true);
			try (DownloadClient client = client(server, "test-secret")) {
				Path destination = directory.resolve("object");
				assertEquals(destination, client.downloadObject(sha1.getBytes(StandardCharsets.UTF_8), destination, object.length, null).get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertArrayEquals(object, Files.readAllBytes(destination));
				assertFalse(client.rangeIgnoredHost, "an honest-length 200 is not a range-ignoring verdict");
			}
			assertEquals(1, server.requests.size(), "the whole file rode one take, no tiling");
			assertTrue(server.ranges.isEmpty(), "the server never answered a range");
		}
	}

	/** A close-framed 200 on a bounded take cannot be judged by length and its body spends the lane, so the verdict fires undrained: the host degrades and the requeue rides one open-ended take. */
	@Test
	void aCloseFramed200OnABoundedTakeThrowsRangeIgnoredAndDegradesTheHost(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			byte[] object = new byte[256 * 1024];
			new SecureRandom().nextBytes(object);
			String sha1 = HashUtils.sha1(object);
			server.store().put(sha1, object);
			server.closeFramedObjects.set(true);
			try (DownloadClient client = client(server, "test-secret")) {
				Path destination = directory.resolve("object");
				var thrown = assertThrows(ExecutionException.class, () -> client.downloadObject(sha1.getBytes(StandardCharsets.UTF_8), destination, object.length, null).get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertInstanceOf(RangeIgnoredException.class, rootCause(thrown));
				assertTrue(client.rangeIgnoredHost, "the close-framed verdict degrades the whole client");
				assertFalse(Files.exists(destination), "the body was never consumed into the destination");

				// The manager requeues the task; under the flag it skips tiling and rides one open-ended take.
				assertEquals(destination, client.downloadObject(sha1.getBytes(StandardCharsets.UTF_8), destination, object.length, null).get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertArrayEquals(object, Files.readAllBytes(destination));
			}
		}
	}

	/** A full-object take whose declared length differs from the expected object size is the length-mismatch verdict: it fails before a body byte is consumed into the destination. */
	@Test
	void aWrongDeclaredLengthOnAFullObjectTakeFailsBeforeConsuming(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			// The server stores 200 bytes under the hash while the caller advertised a 100-byte object: no take can match.
			byte[] object = new byte[200];
			new SecureRandom().nextBytes(object);
			String sha1 = HashUtils.sha1(object);
			server.store().put(sha1, object);
			server.ignoreRanges.set(true);
			try (DownloadClient client = client(server, "test-secret")) {
				Path destination = directory.resolve("object");
				var first = assertThrows(ExecutionException.class, () -> client.downloadObject(sha1.getBytes(StandardCharsets.UTF_8), destination, 100, null).get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertInstanceOf(RangeIgnoredException.class, rootCause(first));
				var second = assertThrows(ExecutionException.class, () -> client.downloadObject(sha1.getBytes(StandardCharsets.UTF_8), destination, 100, null).get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertTrue(rootCause(second).getMessage().contains("does not match the expected object size"), String.valueOf(rootCause(second)));
			}
			assertFalse(Files.exists(directory.resolve("object")), "the body was never consumed into the destination");
		}
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
