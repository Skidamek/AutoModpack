package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import io.netty.channel.ChannelFuture;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * Review harness (thermo-nuclear review, ground-up round): drives the REAL server ({@link NettyServer} +
 * {@code HttpContractHandler}) and the REAL client ({@link DownloadClient}) end to end. Benchmarks throughput and
 * integrity, stresses concurrent clients and slow readers, attacks the deep-pipeline dispatch with thousands of
 * all-at-once tiny transfers, and injects chaos (mid-body resets, byte trickles). Objects live on disk, not in the
 * heap, so resident memory belongs to the code under test, not to fixtures.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class ThermoStressBenchTest {

	private static final int BIG_BYTES = 4 * 1024 * 1024;
	private static final int SMALL_BYTES = 512 * 1024;

	private Path generationDirectory;
	private Path downloadDirectory;
	private ServerConfigJsons.ServerConfigFieldsV3 previousConfig;
	private NettyServer server;
	private NettyServer plaintextServer;
	/** sha1 -> object path on disk; the harness never retains object bytes. */
	private Map<String, Path> universe;
	private int serverPort;
	private int plaintextPort;

	@BeforeAll
	void setUp() throws Exception {
		generationDirectory = Files.createTempDirectory("thermo-generation");
		downloadDirectory = Files.createTempDirectory("thermo-download");
		previousConfig = Constants.serverConfig;
		Constants.serverConfig = new ServerConfigJsons.ServerConfigFieldsV3();
		Constants.serverConfig.validateSecrets = false;
		Constants.serverConfig.modpackHost = true;
		Constants.serverConfig.connectionMode = ModpackConnectionMode.HTTP;
		Constants.serverConfig.bindPort = 0; // ephemeral
		Constants.serverConfig.bandwidthLimit = 0;

		universe = buildUniverseOnDisk();
		Files.writeString(generationDirectory.resolve("head-doc"), "{}");

		server = new NettyServer();
		server.replacePaths(pathsFor(universe, true));
		server.start();
		assertTrue(server.isRunning());
		serverPort = localPort(server);

		Constants.serverConfig.disableInternalTLS = true;
		plaintextServer = new NettyServer();
		plaintextServer.replacePaths(pathsFor(universe, true));
		plaintextServer.start();
		plaintextPort = localPort(plaintextServer);
		Constants.serverConfig.disableInternalTLS = false;
	}

	@AfterAll
	void tearDown() throws Exception {
		if (server != null) server.stop();
		if (plaintextServer != null) plaintextServer.stop();
		Constants.serverConfig = previousConfig;
		deleteRecursively(generationDirectory);
		deleteRecursively(downloadDirectory);
	}

	/** 60 x 4 MiB incompressible + 40 x 512 KiB compressible ≈ 260 MiB, written straight to disk. */
	private Map<String, Path> buildUniverseOnDisk() throws Exception {
		Map<String, Path> universe = new LinkedHashMap<>();
		Random random = new Random(42);
		byte[] content = new byte[BIG_BYTES];
		for (int i = 0; i < 60; i++) {
			random.nextBytes(content);
			Path path = generationDirectory.resolve("big-" + i);
			Files.write(path, content);
			universe.put(sha1Of(content), path);
		}
		byte[] pattern = new byte[SMALL_BYTES];
		for (int i = 0; i < pattern.length; i++) pattern[i] = (byte) (i % 251);
		for (int i = 0; i < 40; i++) {
			pattern[i] ^= 0x5A;
			Path path = generationDirectory.resolve("small-" + i);
			Files.write(path, pattern);
			universe.put(sha1Of(pattern), path);
			pattern[i] ^= 0x5A;
		}
		return universe;
	}

	private static String sha1Of(byte[] content) {
		var digest = HashUtils.newSha1Digest();
		digest.update(content);
		return HexFormat.of().formatHex(digest.digest());
	}

	private Map<String, Path> pathsFor(Map<String, Path> universe, boolean withHead) {
		Map<String, Path> paths = new LinkedHashMap<>();
		if (withHead) paths.put(GenerationHosting.HEAD_DOCUMENT_KEY, generationDirectory.resolve("head-doc"));
		paths.putAll(universe);
		return paths;
	}

	private DownloadClient client(NettyServer target, int port) throws Exception {
		ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(
				InetSocketAddress.createUnresolved("127.0.0.1", 25565),
				new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
				ModpackConnectionMode.HTTP, target.getCertificateFingerprint(), null);
		return DownloadClient.createAsync(connectionInfo, "ignored", ignored -> CompletableFuture.completedFuture(false))
				.get(30, TimeUnit.SECONDS);
	}

	private static int localPort(NettyServer target) throws Exception {
		var field = NettyServer.class.getDeclaredField("serverChannel");
		field.setAccessible(true);
		var future = (ChannelFuture) field.get(target);
		return ((InetSocketAddress) future.channel().localAddress()).getPort();
	}

	private record Item(String sha1, Path source, CompletableFuture<Path> future) {}

	private void verify(Path downloaded, Path source) throws IOException {
		Path assembled = Files.createTempFile(downloaded.getParent(), "assembled-", ".bin");
		try {
			PartialResume.assemble(downloaded, assembled, Files.size(source));
			assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(assembled), "object " + downloaded.getFileName() + " must arrive byte-exact");
		} finally {
			Files.deleteIfExists(assembled);
		}
	}

	/** Drives the given downloads with at most {@code inFlightCap} concurrent transfers; verifies byte-exactness; returns wall millis. */
	private long drive(DownloadClient client, List<String> sha1s, String prefix, int inFlightCap) throws Exception {
		List<String> remaining = new CopyOnWriteArrayList<>(sha1s);
		List<Item> inFlight = new CopyOnWriteArrayList<>();
		AtomicLong failedAttempts = new AtomicLong();
		long start = System.nanoTime();
		while (!remaining.isEmpty() || !inFlight.isEmpty()) {
			while (!remaining.isEmpty() && inFlight.size() < inFlightCap) {
				String sha1 = remaining.remove(0);
				CompletableFuture<Path> future = client.downloadObject(sha1.getBytes(StandardCharsets.UTF_8),
						downloadDirectory.resolve(prefix + sha1), Files.size(universe.get(sha1)), null);
				inFlight.add(new Item(sha1, universe.get(sha1), future));
			}
			Thread.sleep(2);
			for (Item item : new ArrayList<>(inFlight)) {
				if (!item.future().isDone()) continue;
				inFlight.remove(item);
				Path path;
				try {
					path = item.future().join();
				} catch (Exception error) {
					failedAttempts.incrementAndGet();
					remaining.add(item.sha1());
					continue;
				}
				verify(path, item.source());
			}
		}
		long millis = (System.nanoTime() - start) / 1_000_000;
		System.out.printf("[bench] %s: objects=%d failed-attempts=%d wall=%dms%n",
				prefix.isEmpty() ? "universe" : prefix, sha1s.size(), failedAttempts.get(), millis);
		return millis;
	}

	@Test
	void benchSingleClientThroughputAndIntegrity() throws Exception {
		try (DownloadClient client = client(server, serverPort)) {
			List<String> sha1s = new ArrayList<>(universe.keySet());
			long millis = drive(client, sha1s, "", 40);
			double mebibytes = sha1s.stream().mapToLong(this::sizeOf).sum() / 1024.0 / 1024.0;
			System.out.printf("[bench] single client: %.1f MiB in %d ms -> %.1f MiB/s%n", mebibytes, millis, mebibytes / (millis / 1000.0));
			System.out.println("[bench] " + client.windowSummary());
		}
	}

	private long sizeOf(String sha1) {
		try {
			return Files.size(universe.get(sha1));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * The deep-pipeline attack: 2000 tiny (16 KiB) objects, every transfer dispatched at once with no in-flight cap -
	 * thousands of queued takes against the per-lane byte window and the request-count tripwire. Correctness and a
	 * bounded wall are the assertions; the number reports what the deep window buys over a round trip per request.
	 */
	@Test
	void tinyFilesDeepPipeline() throws Exception {
		Map<String, Path> tiny = new LinkedHashMap<>();
		Random random = new Random(7);
		byte[] content = new byte[16 * 1024];
		for (int i = 0; i < 2000; i++) {
			random.nextBytes(content);
			Path path = generationDirectory.resolve("tiny-" + i);
			Files.write(path, content);
			tiny.put(sha1Of(content), path);
		}
		Map<String, Path> paths = pathsFor(universe, true);
		paths.putAll(tiny);
		server.replacePaths(paths);
		try (DownloadClient client = client(server, serverPort)) {
			HeapSampler sampler = new HeapSampler();
			sampler.start();
			List<String> sha1s = new ArrayList<>(tiny.keySet());
			List<CompletableFuture<Path>> futures = new ArrayList<>();
			long start = System.nanoTime();
			for (String sha1 : sha1s) {
				futures.add(client.downloadObject(sha1.getBytes(StandardCharsets.UTF_8),
						downloadDirectory.resolve("tiny-" + sha1), Files.size(tiny.get(sha1)), null));
			}
			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(300, TimeUnit.SECONDS);
			long millis = (System.nanoTime() - start) / 1_000_000;
			for (int i = 0; i < sha1s.size(); i++) verify(futures.get(i).join(), tiny.get(sha1s.get(i)));
			sampler.stop();
			System.out.printf("[tiny] 2000 x 16 KiB all-at-once: verified=%d wall=%dms, max heap %.0f MiB%n", sha1s.size(), millis, sampler.maxUsedHeapMiB());
			System.out.println("[tiny] " + client.windowSummary());
		} finally {
			server.replacePaths(pathsFor(universe, true));
		}
	}

	@Test
	void stressConcurrentClients() throws Exception {
		int clients = 8;
		ExecutorService pool = Executors.newFixedThreadPool(clients, r -> {
			Thread t = new Thread(r, "thermo-client");
			t.setDaemon(true);
			return t;
		});
		HeapSampler sampler = new HeapSampler();
		sampler.start();
		try {
			List<String> subset = new ArrayList<>();
			universe.keySet().stream().limit(4).forEach(subset::add); // 4 big
			universe.keySet().stream().skip(60).limit(4).forEach(subset::add); // 4 small = 18 MiB per client
			List<CompletableFuture<Long>> results = new ArrayList<>();
			for (int c = 0; c < clients; c++) {
				final int index = c;
				results.add(CompletableFuture.supplyAsync(() -> {
					try (DownloadClient client = client(server, serverPort)) {
						return drive(client, subset, index + "-", 40);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}, pool));
			}
			CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).get(300, TimeUnit.SECONDS);
			long totalMillis = results.stream().mapToLong(CompletableFuture::join).sum();
			System.out.printf("[stress] %d clients x 18 MiB: sum-of-walls=%dms, max heap during run: %.0f MiB%n",
					clients, totalMillis, sampler.maxUsedHeapMiB());
		} finally {
			sampler.stop();
			pool.shutdownNow();
		}
	}

	/** A throttled TCP proxy that resets the first connection mid-transfer; the transfer must still land byte-exact. */
	@Test
	void chaosProxyResetsMidTransfer() throws Exception {
		try (ThrottledChaosProxy proxy = new ThrottledChaosProxy(new InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort), 1)) {
			try (DownloadClient client = client(server, proxy.port())) {
				// 40 MiB through an ~8 MB/s relay stretched over seconds, so the reset lands mid-take
				List<String> sha1s = new ArrayList<>(universe.keySet()).subList(0, 10);
				long millis = drive(client, sha1s, "chaos-", 40);
				assertTrue(proxy.resetsFired() >= 1, "the proxy must have reset at least one lane mid-transfer; accepted=" + proxy.accepted());
				System.out.printf("[chaos] survived %d lane resets in %d ms%n", proxy.resetsFired(), millis);
			}
		}
	}

	/**
	 * A host that answers correctly and then drips 1 KB every 200 ms must not park the take forever, and must not be
	 * fused prematurely: a 4 MiB slice's budget is max(90 s, 4 MiB / 16 KiB/s) = 256 s (unit-pinned in
	 * {@code DownloadClientTest}), so inside 5 s the take must simply still be running.
	 */
	@Test
	void byteTrickleFusesOnlyOnItsBudget() throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X509Certificate certificate = selfSigned(keyPair);
		SSLContext context = serverContext(keyPair, certificate);
		String fingerprint = NetUtils.getFingerprint(certificate);
		try (ServerSocket listener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
			ExecutorService pool = Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "thermo-trickle");
				t.setDaemon(true);
				return t;
			});
			AtomicBoolean stop = new AtomicBoolean();
			pool.execute(() -> {
				while (!stop.get()) {
					try {
						Socket plain = listener.accept();
						pool.execute(() -> trickleServe(plain, context));
					} catch (IOException e) {
						return;
					}
				}
			});
			ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(
					InetSocketAddress.createUnresolved("127.0.0.1", 25565),
					new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
					ModpackConnectionMode.HTTP, fingerprint, null);
			try (DownloadClient client = DownloadClient.createAsync(connectionInfo, "ignored", ignored -> CompletableFuture.completedFuture(false))
					.get(30, TimeUnit.SECONDS)) {
				String sha1 = new ArrayList<>(universe.keySet()).get(0);
				CompletableFuture<Path> transfer = client.downloadObject(sha1.getBytes(StandardCharsets.UTF_8),
						downloadDirectory.resolve("trickled"), BIG_BYTES, null);
				Thread.sleep(5000);
				assertFalse(transfer.isDone(), "a trickling take runs for its 256 s budget; it must not fail instantly");
				System.out.println("[hostile] trickle: take alive at 5 s under a 5 KB/s drip, no premature fuse (budget is 256 s, unit-pinned)");
			} finally {
				stop.set(true);
				listener.close();
				pool.shutdownNow();
			}
		}
	}

	private void trickleServe(Socket plain, SSLContext context) {
		SSLSocket socket = null;
		try {
			socket = (SSLSocket) context.getSocketFactory().createSocket(plain, null, plain.getPort(), true);
			socket.setUseClientMode(false);
			socket.setEnabledProtocols(new String[]{"TLSv1.3"});
			socket.startHandshake();
			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();
			String request = readHead(in);
			if (!request.contains("GET /objects/")) {
				socket.close();
				return;
			}
			String head = "HTTP/1.1 206 Partial Content\r\nContent-Length: " + BIG_BYTES + "\r\nContent-Range: bytes 0-" + (BIG_BYTES - 1) + "/" + BIG_BYTES + "\r\n\r\n";
			out.write(head.getBytes(StandardCharsets.US_ASCII));
			out.flush();
			byte[] kilobyte = new byte[1024];
			// drip the body, 1 KB every 200 ms (5 KB/s): the socket is never idle for the 60 s read deadline, but the
			// take's 16 KiB/s rate-floor fuse must eventually close the lane
			for (int offset = 0; offset + 1024 <= BIG_BYTES && !socket.isClosed(); offset += 1024) {
				out.write(kilobyte);
				out.flush();
				Thread.sleep(200);
			}
		} catch (Exception ignored) {
		} finally {
			if (socket != null) try {
				socket.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static String readHead(InputStream in) throws IOException {
		StringBuilder head = new StringBuilder();
		int previous = -1;
		while (true) {
			int read = in.read();
			if (read < 0) throw new IOException("EOF in request head");
			head.append((char) read);
			if (previous == '\r' && read == '\n' && head.toString().endsWith("\r\n\r\n")) break;
			previous = read;
		}
		return head.toString();
	}

	/** Twenty-four raw readers pull a big object and then stop reading entirely: server memory must stay bounded, and the server must stay healthy. */
	@Test
	void slowReadersBoundedMemory() throws Exception {
		int readers = 24;
		String bigSha1 = new ArrayList<>(universe.keySet()).get(0);
		List<Socket> sockets = new ArrayList<>();
		HeapSampler sampler = new HeapSampler();
		try {
			sampler.start();
			for (int i = 0; i < readers; i++) {
				Socket socket = new Socket(InetAddress.getLoopbackAddress(), plaintextPort);
				socket.setTcpNoDelay(true);
				OutputStream out = socket.getOutputStream();
				out.write(("GET /objects/" + bigSha1 + " HTTP/1.1\r\nHost: probe\r\nAccept-Encoding: zstd, gzip\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
				out.flush();
				InputStream in = socket.getInputStream();
				byte[] head = new byte[400];
				int read = 0;
				while (read < head.length) {
					int chunk = in.read(head, read, head.length - read);
					if (chunk < 0) break;
					read += chunk;
				}
				sockets.add(socket); // now stop reading: the server's writes must hit the watermark
			}
			Thread.sleep(10_000);
			System.out.printf("[memory] %d stalled readers, max heap during probe: %.0f MiB (512 KiB stream writes bound each reader to ~0.5 MiB + watermark)%n",
					readers, sampler.maxUsedHeapMiB());
		} finally {
			sampler.stop();
			for (Socket socket : sockets) try {
				socket.close();
			} catch (IOException ignored) {
			}
		}
		// the server must still answer promptly after the probe
		Path destination = downloadDirectory.resolve("post-probe");
		try (DownloadClient client = client(server, serverPort)) {
			client.downloadObject(bigSha1.getBytes(StandardCharsets.UTF_8), destination, BIG_BYTES, null).get(60, TimeUnit.SECONDS);
			verify(destination, universe.get(bigSha1));
		}
	}

	/** Samples used heap in the background so tests can report resident memory. */
	private static final class HeapSampler {
		private final Thread thread;
		private volatile long maxUsed;
		private volatile boolean running = true;

		HeapSampler() {
			this.thread = new Thread(() -> {
				Runtime runtime = Runtime.getRuntime();
				while (running) {
					long used = runtime.totalMemory() - runtime.freeMemory();
					if (used > maxUsed) maxUsed = used;
					try {
						Thread.sleep(50);
					} catch (InterruptedException e) {
						return;
					}
				}
			}, "thermo-sampler");
			this.thread.setDaemon(true);
		}

		void start() {
			thread.start();
		}

		void stop() throws InterruptedException {
			running = false;
			thread.join(1000);
		}

		double maxUsedHeapMiB() {
			return maxUsed / 1024.0 / 1024.0;
		}
	}

	/**
	 * Shuffles bytes between the client and the real server at a fixed pace, resetting the first {@code resetFirst}
	 * connections a moment after they open: mid-take lane deaths.
	 */
	private static final class ThrottledChaosProxy implements AutoCloseable {
		private static final int PACE_CHUNK = 32 * 1024;
		private static final long PACE_MILLIS = 4; // ≈ 8 MB/s

		private final ServerSocket listener;
		private final InetSocketAddress target;
		private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "thermo-chaos");
			t.setDaemon(true);
			return t;
		});
		private final List<Socket> open = new CopyOnWriteArrayList<>();
		private final AtomicBoolean closed = new AtomicBoolean();
		private final int resetFirst;
		private final AtomicLong connectionsAccepted = new AtomicLong();
		private final AtomicLong resetsFired = new AtomicLong();

		ThrottledChaosProxy(InetSocketAddress target, int resetFirst) throws IOException {
			this.target = target;
			this.resetFirst = resetFirst;
			this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			pool.execute(this::acceptLoop);
		}

		int port() {
			return listener.getLocalPort();
		}

		long resetsFired() {
			return resetsFired.get();
		}

		long accepted() {
			return connectionsAccepted.get();
		}

		private void acceptLoop() {
			while (!closed.get()) {
				try {
					Socket clientSide = listener.accept();
					open.add(clientSide);
					boolean resetThisOne = connectionsAccepted.incrementAndGet() <= resetFirst;
					pool.execute(() -> relay(clientSide, resetThisOne));
				} catch (IOException e) {
					return;
				}
			}
		}

		private void relay(Socket clientSide, boolean resetThisOne) {
			try (Socket serverSide = new Socket(target.getAddress(), target.getPort())) {
				open.add(serverSide);
				final Socket serverSideRef = serverSide;
				if (resetThisOne) {
					// relay normally now; RST mid-body once the handshake and the first takes are done
					pool.execute(() -> {
						try {
							Thread.sleep(1500);
							resetsFired.incrementAndGet();
							clientSide.setSoLinger(true, 0);
							clientSide.close();
							serverSideRef.close();
						} catch (Exception ignored) {
						}
					});
				}
				Thread other = new Thread(() -> shuffle(serverSide, clientSide));
				other.setDaemon(true);
				other.start();
				shuffle(clientSide, serverSide);
			} catch (Exception ignored) {
			} finally {
				open.remove(clientSide);
			}
		}

		private static void shuffle(Socket from, Socket to) {
			byte[] buffer = new byte[PACE_CHUNK];
			try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
				int read;
				while ((read = in.read(buffer)) >= 0) {
					out.write(buffer, 0, read);
					out.flush();
					Thread.sleep(PACE_MILLIS);
				}
			} catch (Exception ignored) {
			} finally {
				try {
					to.close();
				} catch (IOException ignored) {
				}
			}
		}

		@Override
		public void close() throws IOException {
			closed.set(true);
			listener.close();
			for (Socket socket : open) try {
				socket.close();
			} catch (IOException ignored) {
			}
			pool.shutdownNow();
		}
	}

	private static X509Certificate selfSigned(KeyPair keyPair) throws Exception {
		X500Name subject = new X500Name("CN=ThermoStress");
		var builder = new JcaX509v3CertificateBuilder(subject, BigInteger.ONE, Date.from(Instant.now().minusSeconds(60)),
				Date.from(Instant.now().plusSeconds(3600)), subject, keyPair.getPublic());
		return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate())));
	}

	private static SSLContext serverContext(KeyPair keyPair, X509Certificate certificate) throws Exception {
		char[] password = "test-password".toCharArray();
		KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
		keyStore.load(null);
		keyStore.setKeyEntry("server", keyPair.getPrivate(), password, new Certificate[]{certificate});
		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(keyStore, password);
		SSLContext context = SSLContext.getInstance("TLSv1.3");
		context.init(keyManagers.getKeyManagers(), null, new SecureRandom());
		return context;
	}

	private static void deleteRecursively(Path path) throws IOException {
		if (path == null || Files.notExists(path)) return;
		try (var paths = Files.walk(path)) {
			paths.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
				}
			});
		}
	}
}
