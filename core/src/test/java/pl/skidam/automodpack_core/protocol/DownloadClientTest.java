package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_CHUNK_SIZE_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_COMPRESSION_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_ECHO_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.END_OF_TRANSMISSION;
import static pl.skidam.automodpack_core.protocol.NetUtils.FILE_REQUEST_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAX_CHUNK_SIZE;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;

class DownloadClientTest {

	@Test
	void fileFrameCopyDoesNotOverflowForSizesAbove2GiB() {
		long remaining = 2230765895L;
		assertEquals(-2064201401, (int) remaining);
		assertEquals(1024, DownloadClient.writableFrameBytes(1024, remaining));
		assertEquals(MAX_CHUNK_SIZE, DownloadClient.writableFrameBytes(MAX_CHUNK_SIZE, remaining));
		assertEquals(100, DownloadClient.writableFrameBytes(1024, 100L));
		assertEquals(0, DownloadClient.writableFrameBytes(1024, 0L));
	}

	@Test
	void localDestinationOpenFailureHasTypedStorageBoundary(@TempDir Path directory) throws Exception {
		Path destination = Files.createDirectory(directory.resolve("destination"));

		assertThrows(LocalStorageException.class, () -> LocalFileWriter.open(destination));
	}

	@Test
	void recognizesOnlyGenuinelySelfSignedCertificates() throws Exception {
		X509Certificate selfSigned = NetUtils.selfSign(NetUtils.generateKeyPair());
		KeyPair issuerKeyPair = NetUtils.generateKeyPair();
		X509Certificate issuer = NetUtils.selfSign(issuerKeyPair);
		X509Certificate issued = issueCertificate(new X500Name(issuer.getSubjectX500Principal().getName()), new X500Name("CN=Issued"), null,
				NetUtils.generateKeyPair(), issuerKeyPair, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));

		assertTrue(DownloadClient.isSelfSigned(selfSigned));
		assertFalse(DownloadClient.isSelfSigned(issued));
	}

	@Test
	void recognizesExpiredCertificateAsSelfSigned() throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X500Name subject = new X500Name("CN=Expired AutoModpack Certificate");
		X509Certificate certificate = issueCertificate(subject, subject, null, keyPair, keyPair, Instant.now().minusSeconds(7200),
				Instant.now().minusSeconds(3600));

		assertTrue(DownloadClient.isSelfSigned(certificate));
	}

	@Test
	void exactAndSessionPinsConstrainEveryConnection() throws Exception {
		X509Certificate accepted = NetUtils.selfSign(NetUtils.generateKeyPair());
		X509Certificate changed = NetUtils.selfSign(NetUtils.generateKeyPair());
		String fingerprint = NetUtils.getFingerprint(accepted);

		var configuredTrust = new CustomizableTrustManager.SessionTrust("origin.example:25565", fingerprint);
		var configuredManager = new CustomizableTrustManager(configuredTrust, null);
		assertDoesNotThrow(() -> configuredManager.checkServerTrusted(new X509Certificate[]{accepted}, "RSA"));
		assertThrows(CertificatePinMismatchException.class,
				() -> configuredManager.checkServerTrusted(new X509Certificate[]{changed}, "RSA"));

		var sessionTrust = new CustomizableTrustManager.SessionTrust("origin.example:25565", null);
		sessionTrust.accept(accepted);
		var sessionManager = new CustomizableTrustManager(sessionTrust, null);
		assertDoesNotThrow(() -> sessionManager.checkServerTrusted(new X509Certificate[]{accepted}, "RSA"));
		assertThrows(CertificatePinMismatchException.class, () -> sessionManager.checkServerTrusted(new X509Certificate[]{changed}, "RSA"));
	}

	@Test
	void deferredTrustSendsNoApplicationBytesAndReusesSocket() throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X509Certificate certificate = NetUtils.selfSign(keyPair);
		CompletableFuture<Boolean> decision = new CompletableFuture<>();

		try (TransferServer server = new TransferServer(keyPair, certificate)) {
			ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
					new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.DIRECT, null, null);
			CompletableFuture<DownloadClient> clientFuture = DownloadClient.createAsync(connectionInfo, new byte[32], ignored -> decision);

			assertEquals(-1, server.earlyApplicationByte().get(5, TimeUnit.SECONDS));
			assertFalse(clientFuture.isDone());
			decision.complete(true);

			try (DownloadClient ignored = clientFuture.get(5, TimeUnit.SECONDS)) {
				server.configured().get(5, TimeUnit.SECONDS);
				assertEquals(1, server.acceptedConnections());
			}
		}
	}

	@Test
	void rejectedDeferredTrustClosesWithoutReconnect() throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X509Certificate certificate = NetUtils.selfSign(keyPair);
		CompletableFuture<Boolean> decision = new CompletableFuture<>();

		try (TransferServer server = new TransferServer(keyPair, certificate)) {
			ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
					new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.DIRECT, null, null);
			CompletableFuture<DownloadClient> clientFuture = DownloadClient.createAsync(connectionInfo, new byte[32], ignored -> decision);

			assertEquals(-1, server.earlyApplicationByte().get(5, TimeUnit.SECONDS));
			decision.complete(false);
			assertThrows(Exception.class, () -> clientFuture.get(5, TimeUnit.SECONDS));
			assertEquals(1, server.acceptedConnections());
		}
	}

	@Test
	void lazyPoolCapsAtFiveAndQueuesSixthRequest(@TempDir Path directory) throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X509Certificate certificate = NetUtils.selfSign(keyPair);
		String fingerprint = NetUtils.getFingerprint(certificate);

		try (LeasingServer server = new LeasingServer(keyPair, certificate)) {
			ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
					new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.DIRECT, fingerprint, null);
			try (DownloadClient client = DownloadClient.createAsync(connectionInfo, new byte[32], ignored -> CompletableFuture.completedFuture(false)).get(5,
					TimeUnit.SECONDS)) {
				List<CompletableFuture<Path>> downloads = new ArrayList<>();
				for (int i = 0; i < 6; i++) downloads.add(client.downloadFile(new byte[0], directory.resolve("download-" + i), null));

				assertTrue(server.firstFiveRequests().await(5, TimeUnit.SECONDS));
				assertEquals(5, server.acceptedConnections());
				assertFalse(server.sixthRequest().isDone());

				server.allowResponses(1);
				server.sixthRequest().get(5, TimeUnit.SECONDS);
				assertEquals(5, server.acceptedConnections());

				server.allowResponses(5);
				CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
			}
		}
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

	private static X509Certificate issueCertificate(X500Name issuer, X500Name subject, String dnsName, KeyPair subjectKeyPair, KeyPair signingKeyPair,
			Instant notBefore, Instant notAfter) throws Exception {
		var builder = new JcaX509v3CertificateBuilder(issuer, BigInteger.ONE, Date.from(notBefore), Date.from(notAfter), subject, subjectKeyPair.getPublic());
		if (dnsName != null) builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.dNSName, dnsName)));
		var signer = new JcaContentSignerBuilder("SHA256WithRSA").build(signingKeyPair.getPrivate());
		return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
	}

	private static final class LeasingServer implements AutoCloseable {
		private final SSLServerSocket server;
		private final ExecutorService executor = Executors.newCachedThreadPool();
		private final List<SSLSocket> sockets = new CopyOnWriteArrayList<>();
		private final AtomicInteger acceptedConnections = new AtomicInteger();
		private final CountDownLatch firstFiveRequests = new CountDownLatch(5);
		private final CompletableFuture<Void> sixthRequest = new CompletableFuture<>();
		private final Semaphore responsePermits = new Semaphore(0);
		private volatile boolean closed;

		LeasingServer(KeyPair keyPair, X509Certificate certificate) throws Exception {
			server = (SSLServerSocket) serverContext(keyPair, certificate).getServerSocketFactory().createServerSocket(0, 5,
					InetAddress.getLoopbackAddress());
			server.setEnabledProtocols(new String[]{"TLSv1.3"});
			executor.execute(this::acceptConnections);
		}

		int port() {
			return server.getLocalPort();
		}

		int acceptedConnections() {
			return acceptedConnections.get();
		}

		CountDownLatch firstFiveRequests() {
			return firstFiveRequests;
		}

		CompletableFuture<Void> sixthRequest() {
			return sixthRequest;
		}

		void allowResponses(int count) {
			responsePermits.release(count);
		}

		private void acceptConnections() {
			while (!closed) {
				try {
					SSLSocket socket = (SSLSocket) server.accept();
					sockets.add(socket);
					acceptedConnections.incrementAndGet();
					executor.execute(() -> serve(socket));
				} catch (IOException e) {
					if (!closed) sixthRequest.completeExceptionally(e);
				}
			}
		}

		private void serve(SSLSocket socket) {
			try {
				socket.setEnabledProtocols(new String[]{"TLSv1.3"});
				socket.startHandshake();
				DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
				DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

				int version = in.readUnsignedByte();
				if (in.readUnsignedByte() != CONFIGURATION_COMPRESSION_TYPE) throw new IOException("Unexpected compression request");
				in.readUnsignedByte();
				out.writeByte(version);
				out.writeByte(CONFIGURATION_COMPRESSION_TYPE);
				out.writeByte(CompressionType.GZIP.wireId());
				out.flush();

				version = in.readUnsignedByte();
				if (in.readUnsignedByte() != CONFIGURATION_CHUNK_SIZE_TYPE) throw new IOException("Unexpected chunk request");
				int chunkSize = in.readInt();
				out.writeByte(version);
				out.writeByte(CONFIGURATION_CHUNK_SIZE_TYPE);
				out.writeInt(chunkSize);
				out.flush();

				in.readUnsignedByte();
				if (in.readUnsignedByte() != CONFIGURATION_ECHO_TYPE) throw new IOException("Unexpected echo request");

				CompressionCodec codec = CompressionFactory.createCodec(CompressionType.GZIP);
				while (!closed && !socket.isClosed()) {
					byte[] request = readFrame(in, codec);
					if (request.length < 2 || request[1] != FILE_REQUEST_TYPE) throw new IOException("Unexpected file request");
					if (firstFiveRequests.getCount() > 0) {
						firstFiveRequests.countDown();
					} else {
						sixthRequest.complete(null);
					}
					responsePermits.acquire();
					writeFrame(out, codec, new byte[]{request[0], END_OF_TRANSMISSION});
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (IOException ignored) {
			}
		}

		private static byte[] readFrame(DataInputStream in, CompressionCodec codec) throws IOException {
			int compressedLength = in.readInt();
			int originalLength = in.readInt();
			byte[] compressed = in.readNBytes(compressedLength);
			if (compressed.length != compressedLength) throw new EOFException("Incomplete request frame");
			return codec.decompress(compressed, 0, compressedLength, originalLength);
		}

		private static void writeFrame(DataOutputStream out, CompressionCodec codec, byte[] payload) throws IOException {
			byte[] compressed = codec.compress(payload);
			out.writeInt(compressed.length);
			out.writeInt(payload.length);
			out.write(compressed);
			out.flush();
		}

		@Override
		public void close() throws Exception {
			closed = true;
			server.close();
			responsePermits.release(6);
			for (SSLSocket socket : sockets) socket.close();
			executor.shutdownNow();
		}
	}

	private static final class TransferServer implements AutoCloseable {
		private final SSLServerSocket server;
		private final ExecutorService executor = Executors.newSingleThreadExecutor();
		private final AtomicInteger acceptedConnections = new AtomicInteger();
		private final CompletableFuture<Integer> earlyApplicationByte = new CompletableFuture<>();
		private final CompletableFuture<Void> configured = new CompletableFuture<>();
		private volatile SSLSocket socket;

		TransferServer(KeyPair keyPair, X509Certificate certificate) throws Exception {
			server = (SSLServerSocket) serverContext(keyPair, certificate).getServerSocketFactory().createServerSocket(0, 5,
					InetAddress.getLoopbackAddress());
			server.setEnabledProtocols(new String[]{"TLSv1.3"});
			executor.execute(this::serve);
		}

		int port() {
			return server.getLocalPort();
		}

		int acceptedConnections() {
			return acceptedConnections.get();
		}

		CompletableFuture<Integer> earlyApplicationByte() {
			return earlyApplicationByte;
		}

		CompletableFuture<Void> configured() {
			return configured;
		}

		private void serve() {
			try {
				socket = (SSLSocket) server.accept();
				acceptedConnections.incrementAndGet();
				socket.setEnabledProtocols(new String[]{"TLSv1.3"});
				socket.startHandshake();
				DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
				DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

				socket.setSoTimeout(300);
				try {
					earlyApplicationByte.complete(in.readUnsignedByte());
				} catch (SocketTimeoutException e) {
					earlyApplicationByte.complete(-1);
				}

				socket.setSoTimeout(5000);
				int version = in.readUnsignedByte();
				int compressionType = in.readUnsignedByte();
				int compression = in.readUnsignedByte();
				if (compressionType != CONFIGURATION_COMPRESSION_TYPE) throw new IOException("Unexpected compression request");
				out.writeByte(version);
				out.writeByte(compressionType);
				out.writeByte(compression);
				out.flush();

				version = in.readUnsignedByte();
				int chunkType = in.readUnsignedByte();
				int chunkSize = in.readInt();
				if (chunkType != CONFIGURATION_CHUNK_SIZE_TYPE) throw new IOException("Unexpected chunk request");
				out.writeByte(version);
				out.writeByte(chunkType);
				out.writeInt(chunkSize);
				out.flush();

				in.readUnsignedByte();
				if (in.readUnsignedByte() != CONFIGURATION_ECHO_TYPE) throw new IOException("Unexpected echo request");
				configured.complete(null);
			} catch (Exception e) {
				if (!earlyApplicationByte.isDone()) earlyApplicationByte.completeExceptionally(e);
				if (!configured.isDone()) configured.completeExceptionally(e);
			}
		}

		@Override
		public void close() throws Exception {
			if (socket != null) socket.close();
			server.close();
			executor.shutdownNow();
		}
	}
}
