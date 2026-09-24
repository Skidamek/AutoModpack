package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * One trust manager and one SSLContext per DownloadClient: the deferred certificate state is keyed per candidate
 * socket, so two candidates deferring through the same manager never read each other's deferral, and the JDK's TLS
 * session cache lives in the shared context, so lanes past the first resume instead of re-entering the trust ladder.
 */
class TlsSessionResumptionTest {
	/** Generous bound for loopback handshakes that complete in milliseconds when warm; cold CI runners have blown past five seconds here. */
	private static final int AWAIT_SECONDS = 20;
	/** The keepalive never ticks within a test; the parked ladder is resolved by the completing callback. */
	private static final Duration NO_HEARTBEATS = Duration.ofHours(1);

	/** Two candidates presenting different self-signed certificates defer into per-socket slots of one shared manager: each validation reads exactly its own certificate and neither poisons the other. */
	@Test
	void deferredStateIsKeyedPerSocketNotPerManager() throws Exception {
		try (ConditionalFetchTest.ContractServer serverA = new ConditionalFetchTest.ContractServer();
				ConditionalFetchTest.ContractServer serverB = new ConditionalFetchTest.ContractServer()) {
			var sessionTrustA = new CustomizableTrustManager.SessionTrust("127.0.0.1", null);
			var sessionTrustB = new CustomizableTrustManager.SessionTrust("127.0.0.1", null);
			// Separate session trusts: one SessionTrust pins one certificate by design, and the isolation under test here is the manager's per-socket state.
			var manager = new CustomizableTrustManager(sessionTrustA, null);
			SSLContext context = CandidateTrustValidation.newSslContext(manager);

			List<X509Certificate> askedFor = new CopyOnWriteArrayList<>();
			try (SSLSocket socketA = handshake(serverA, context); SSLSocket socketB = handshake(serverB, context)) {
				assertEquals(serverA.fingerprint(), NetUtils.getFingerprint(manager.getDeferredCertificate(socketA)), "the first candidate deferred its own certificate");
				assertEquals(serverB.fingerprint(), NetUtils.getFingerprint(manager.getDeferredCertificate(socketB)), "the second candidate deferred its own certificate");
				assertNotEquals(serverA.fingerprint(), serverB.fingerprint(), "the two servers present independent certificates");

				validate(new CandidateTrustValidation.Candidate(socketA, manager, sessionTrustA, "127.0.0.1", "127.0.0.1",
						certificate -> {
							askedFor.add(certificate);
							return CompletableFuture.completedFuture(true);
						}, () -> true, hostHeader(serverA), null));
				assertEquals(serverA.fingerprint(), NetUtils.getFingerprint(askedFor.get(0)), "the ladder judged the first socket's certificate");
				assertNull(manager.getDeferredCertificate(socketA), "a judged candidate's deferral is spent");
				assertEquals(serverB.fingerprint(), NetUtils.getFingerprint(manager.getDeferredCertificate(socketB)), "the first validation left the second deferral untouched");

				validate(new CandidateTrustValidation.Candidate(socketB, manager, sessionTrustB, "127.0.0.1", "127.0.0.1",
						certificate -> {
							askedFor.add(certificate);
							return CompletableFuture.completedFuture(true);
						}, () -> true, hostHeader(serverB), null));
				assertEquals(serverB.fingerprint(), NetUtils.getFingerprint(askedFor.get(1)), "the ladder judged the second socket's certificate");
				assertNull(manager.getDeferredCertificate(socketB));
			}
		}
	}

	/** Two lanes over one client context: the first contact defers once, the second lane's handshake never asks the trust ladder again. */
	@Test
	void theSecondLaneNeverRetriggersTheTrustCallback(@TempDir Path directory) throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			byte[] object = "a-resumption-object".getBytes(StandardCharsets.UTF_8);
			String sha1 = HashUtils.sha1(object);
			server.store().put(sha1, object);
			List<X509Certificate> deferred = new CopyOnWriteArrayList<>();
			ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
					new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.MAGIC, null, null);
			try (DownloadClient client = DownloadClient.createAsync(connectionInfo, "test-secret", certificate -> {
				deferred.add(certificate);
				return CompletableFuture.completedFuture(true);
			}, NO_HEARTBEATS).get(AWAIT_SECONDS, TimeUnit.SECONDS)) {
				int takesPerLane = (int) (NetUtils.PIPELINE_WINDOW_BYTES / NetUtils.WIRE_CHUNK_BYTES);
				server.expectPipeline(takesPerLane + 1);
				List<CompletableFuture<Path>> downloads = new ArrayList<>();
				for (int i = 0; i < takesPerLane + 1; i++) {
					downloads.add(client.downloadSmallObject(sha1.getBytes(StandardCharsets.UTF_8), directory.resolve("download-" + i), -1L, null));
				}
				CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				for (CompletableFuture<Path> download : downloads) {
					assertArrayEquals(object, Files.readAllBytes(download.getNow(null)));
				}
				assertEquals(2, server.connections.get(), "the request past one lane's window opened the second lane");
			}
			assertEquals(1, deferred.size(), () -> "the trust ladder defers exactly once per client, saw " + deferred.size());
		}
	}

	/**
	 * A lane that arrives without its own deferral - in production, a resumed session, whose handshake never
	 * enters the trust manager - is judged by the pin and the session's accepted leaf alone: while the first
	 * contact is still parked in the player's decision, such a lane fails closed and never reaches the player.
	 */
	@Test
	void aLaneWithoutADeferralFailsClosedWhileTheFirstContactIsStillBeingJudged() throws Exception {
		try (ConditionalFetchTest.ContractServer server = new ConditionalFetchTest.ContractServer()) {
			var sessionTrust = new CustomizableTrustManager.SessionTrust("127.0.0.1", null);
			var manager = new CustomizableTrustManager(sessionTrust, null);
			SSLContext context = CandidateTrustValidation.newSslContext(manager);

			try (SSLSocket socketA = handshake(server, context)) {
				assertNotNull(manager.getDeferredCertificate(socketA), "lane one deferred");

				List<X509Certificate> prompted = new CopyOnWriteArrayList<>();
				CompletableFuture<Boolean> decisionA = new CompletableFuture<>();
				CompletableFuture<Void> judgedA = CandidateTrustValidation.validate(new CandidateTrustValidation.Candidate(socketA, manager, sessionTrust, "127.0.0.1", "127.0.0.1",
						certificate -> {
							prompted.add(certificate);
							return decisionA;
						}, () -> true, hostHeader(server), null), NO_HEARTBEATS);
				assertEquals(1, prompted.size(), "the first contact reaches the player");

				// The second lane presents the same not-yet-accepted certificate with no deferral of its own - the
				// shape a resumed session arrives in. It fails closed and never reaches the player, whatever the
				// first lane's decision turns out to be.
				var shadowManager = new CustomizableTrustManager(sessionTrust, null);
				SSLContext shadowContext = CandidateTrustValidation.newSslContext(shadowManager);
				try (SSLSocket socketB = handshake(server, shadowContext)) {
					assertNotNull(shadowManager.getDeferredCertificate(socketB), "the shadow manager caught lane two's deferral");
					assertNull(manager.getDeferredCertificate(socketB), "to the client's manager, lane two arrives with no deferral: the resumed session's shape");
					CompletableFuture<Void> judgedB = CandidateTrustValidation.validate(new CandidateTrustValidation.Candidate(socketB, manager, sessionTrust, "127.0.0.1", "127.0.0.1",
							certificate -> {
								prompted.add(certificate);
								return CompletableFuture.completedFuture(true);
							}, () -> true, hostHeader(server), null), NO_HEARTBEATS);
					var failureB = assertThrows(ExecutionException.class, judgedB::get, "the deferral-less lane fails closed while the first contact is unjudged");
					assertFalse(failureB.getCause() instanceof AssertionError, "the deferral-less lane must never reach the player");
					assertEquals(1, prompted.size(), "only the fresh handshake's certificate reaches the player");
				}

				decisionA.complete(false);
				var failureA = assertThrows(ExecutionException.class, judgedA::get, "the ladder surfaces the player's rejection");
				assertTrue(failureA.getCause() instanceof IOException, "the rejection reads as a connection failure");
			}
		}
	}

	/** Runs the ladder over one candidate socket and fails the test if the decision does not land. */
	private static void validate(CandidateTrustValidation.Candidate candidate) throws Exception {
		CandidateTrustValidation.validate(candidate, NO_HEARTBEATS).get(AWAIT_SECONDS, TimeUnit.SECONDS);
	}

	private static String hostHeader(ConditionalFetchTest.ContractServer server) {
		return "127.0.0.1:" + server.port();
	}

	/** One MAGIC-mode TLS candidate handshaked through the given shared context; the self-signed answer defers into the manager under this socket. */
	private static SSLSocket handshake(ConditionalFetchTest.ContractServer server, SSLContext context) throws Exception {
		Socket plain = new Socket(InetAddress.getLoopbackAddress(), server.port());
		plain.setSoTimeout(NetUtils.NETWORK_TIMEOUT_MILLIS);
		DataOutputStream out = new DataOutputStream(new BufferedOutputStream(plain.getOutputStream()));
		DataInputStream in = new DataInputStream(new BufferedInputStream(plain.getInputStream()));
		byte[] hostBytes = hostHeader(server).getBytes(StandardCharsets.UTF_8);
		out.writeInt(NetUtils.MAGIC_AMMH);
		out.writeShort(hostBytes.length);
		out.write(hostBytes);
		out.flush();
		if (in.readInt() != NetUtils.MAGIC_AMOK) throw new IOException("Invalid response from server");
		SSLSocket tls = CandidateTrustValidation.wrapWithTls(plain, context, "127.0.0.1", server.port());
		tls.setSoTimeout(0);
		return tls;
	}

}
