package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;

/**
 * The conditional and ranged client behavior over a real TLS server: documents answered UNCHANGED on a matching
 * expected hash, the hash-compare verdict when the server ignores the condition, object resume through a ranged
 * request, and a null secret carried as the protocol's zero field.
 */
class ConditionalFetchTest {

	@Test
	void nullSecretIsCarriedAsTheProtocolsZeroField(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			server.store.put("head", "head-document".getBytes(StandardCharsets.UTF_8));
			try (DownloadClient client = client(server, null)) {
				client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), directory.resolve("head"), null, null).get(5, TimeUnit.SECONDS);
			}
			assertArrayEquals(new byte[32], server.firstSecret().get(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void cooperatingServerAnswersUnchangedForMatchingHeadAndJournal(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			byte[] head = "head-document".getBytes(StandardCharsets.UTF_8);
			byte[] journal = "journal-line\n".getBytes(StandardCharsets.UTF_8);
			server.store.put("head", head);
			server.store.put("journal", journal);
			try (DownloadClient client = client(server, new byte[32])) {
				var unchangedHead = client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), directory.resolve("head"), HashUtils.sha1(head), null).get(5, TimeUnit.SECONDS);
				assertNull(unchangedHead.path());
				assertTrue(unchangedHead.unchanged());

				var unchangedJournal = client.downloadDocument("journal".getBytes(StandardCharsets.UTF_8), directory.resolve("journal"), HashUtils.sha1(journal), null).get(5, TimeUnit.SECONDS);
				assertNull(unchangedJournal.path());
				assertTrue(unchangedJournal.unchanged());
				assertFalse(Files.exists(directory.resolve("head")));
				assertFalse(Files.exists(directory.resolve("journal")));
			}
		}
	}

	@Test
	void staleDocumentStreamsAndStaysFetched(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			byte[] oldHead = "old-head-document".getBytes(StandardCharsets.UTF_8);
			byte[] newHead = "new-and-longer-head-document".getBytes(StandardCharsets.UTF_8);
			server.store.put("head", oldHead);
			try (DownloadClient client = client(server, new byte[32])) {
				Path destination = directory.resolve("head");
				var first = client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), destination, HashUtils.sha1(oldHead), null).get(5, TimeUnit.SECONDS);
				assertTrue(first.unchanged());

				server.store.put("head", newHead);
				var second = client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), destination, HashUtils.sha1(oldHead), null).get(5, TimeUnit.SECONDS);
				assertFalse(second.unchanged());
				assertEquals(destination, second.path());
				assertArrayEquals(newHead, Files.readAllBytes(destination));
			}
		}
	}

	@Test
	void nonCooperatingServerStillReadsUnchangedThroughTheBodyHash(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			server.cooperate.set(false);
			byte[] head = "head-document-that-the-server-streams-anyway".getBytes(StandardCharsets.UTF_8);
			server.store.put("head", head);
			try (DownloadClient client = client(server, new byte[32])) {
				Path destination = directory.resolve("head");
				// The host ignored the conditional and sent the full body; the hash-compare is the ground truth.
				var fetch = client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), destination, HashUtils.sha1(head), null).get(5, TimeUnit.SECONDS);
				assertEquals(destination, fetch.path());
				assertTrue(fetch.unchanged());
				assertArrayEquals(head, Files.readAllBytes(destination));
			}
		}
	}

	@Test
	void rangedRequestAppendsBehindTheStoredPartialAndPromotionVerifies(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			byte[] object = new byte[256 * 1024];
			new SecureRandom().nextBytes(object);
			String sha1 = HashUtils.sha1(object);
			server.store.put(sha1, object);

			Path partial = directory.resolve("partial");
			Files.write(partial, Arrays.copyOf(object, 100_000));

			try (DownloadClient client = client(server, new byte[32])) {
				client.downloadFile(sha1.getBytes(StandardCharsets.UTF_8), partial, 100_000, null).get(5, TimeUnit.SECONDS);
			}
			assertArrayEquals(object, Files.readAllBytes(partial));
			assertTrue(FileIntegrity.matches(partial, object.length, sha1));

			Path storeFile = directory.resolve("object.bin");
			VerifiedFileTransfer.promoteAtomic(partial, storeFile, object.length, sha1);
			assertTrue(Files.exists(storeFile));
		}
	}

	@Test
	void rangedRequestAtObjectSizeCompletesWithoutTouchingTheDestination(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			byte[] object = "complete-object-bytes".getBytes(StandardCharsets.UTF_8);
			String sha1 = HashUtils.sha1(object);
			server.store.put(sha1, object);

			Path destination = directory.resolve("partial");
			Files.write(destination, object);

			try (DownloadClient client = client(server, new byte[32])) {
				client.downloadFile(sha1.getBytes(StandardCharsets.UTF_8), destination, object.length, null).get(5, TimeUnit.SECONDS);
			}
			assertArrayEquals(object, Files.readAllBytes(destination));
		}
	}

	private static DownloadClient client(ContractServer server, byte[] secretBytes) throws Exception {
		ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
				new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.DIRECT, server.fingerprint(), null);
		return DownloadClient.createAsync(connectionInfo, secretBytes, ignored -> CompletableFuture.completedFuture(false)).get(5, TimeUnit.SECONDS);
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

	/** One TLS server speaking the FILE_REQUEST side of the protocol against an in-memory object store. */
	static final class ContractServer implements AutoCloseable {
		private final SSLServerSocket server;
		private final ExecutorService executor = Executors.newCachedThreadPool();
		private final Map<String, byte[]> store = new ConcurrentHashMap<>();

		Map<String, byte[]> store() {
			return store;
		}
		private final AtomicBoolean cooperate = new AtomicBoolean(true);
		private final AtomicBoolean secretRecorded = new AtomicBoolean();
		private final CompletableFuture<byte[]> firstSecret = new CompletableFuture<>();
		private final X509Certificate certificate;
		private volatile boolean closed;

		ContractServer() throws Exception {
			KeyPair keyPair = NetUtils.generateKeyPair();
			certificate = selfSigned(keyPair);
			server = (SSLServerSocket) serverContext(keyPair, certificate).getServerSocketFactory().createServerSocket(0, 5, InetAddress.getLoopbackAddress());
			server.setEnabledProtocols(new String[]{"TLSv1.3"});
			executor.execute(this::acceptConnections);
		}

		private static X509Certificate selfSigned(KeyPair keyPair) throws Exception {
			X500Name subject = new X500Name("CN=AutoModpack ConditionalFetchTest");
			var builder = new JcaX509v3CertificateBuilder(subject, BigInteger.ONE, Date.from(Instant.now().minusSeconds(60)),
					Date.from(Instant.now().plusSeconds(3600)), subject, keyPair.getPublic());
			return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate())));
		}

		int port() {
			return server.getLocalPort();
		}

		String fingerprint() throws Exception {
			return NetUtils.getFingerprint(certificate);
		}

		CompletableFuture<byte[]> firstSecret() {
			return firstSecret;
		}

		private void acceptConnections() {
			while (!closed) {
				try {
					SSLSocket socket = (SSLSocket) server.accept();
					executor.execute(() -> serve(socket));
				} catch (IOException e) {
					if (!closed) return;
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
				out.writeByte(CompressionType.NONE.wireId());
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

				CompressionCodec codec = CompressionFactory.createCodec(CompressionType.NONE);
				while (!closed && !socket.isClosed()) {
					byte[] request = readFrame(in, codec);
					if (request.length < 2 || request[1] != FILE_REQUEST_TYPE) throw new IOException("Unexpected request type: " + request[1]);
					serveRequest(out, codec, request[0], request);
				}
			} catch (Exception ignored) {
			}
		}

		private void serveRequest(DataOutputStream out, CompressionCodec codec, byte version, byte[] request) throws IOException {
			ByteBuffer wrap = ByteBuffer.wrap(request);
			wrap.position(2);
			byte[] secret = new byte[32];
			wrap.get(secret);
			if (secretRecorded.compareAndSet(false, true)) firstSecret.complete(secret);

			int keyLength = wrap.getInt();
			byte[] keyBytes = new byte[keyLength];
			wrap.get(keyBytes);
			String key = new String(keyBytes, StandardCharsets.UTF_8);
			byte flags = wrap.get();
			String expected = null;
			long offset = 0;
			if ((flags & FILE_REQUEST_EXPECTED_SHA1_FLAG) != 0) {
				byte[] expectedBytes = new byte[40];
				wrap.get(expectedBytes);
				expected = new String(expectedBytes, StandardCharsets.UTF_8);
			}
			if ((flags & FILE_REQUEST_OFFSET_FLAG) != 0) offset = wrap.getLong();

			byte[] content = store.get(key);
			if (content == null) {
				writeFrame(out, codec, error(version, "File not found", ERROR_CODE_GENERIC));
				return;
			}
			if (expected != null && cooperate.get() && HashUtils.sha1(content).equals(expected)) {
				writeFrame(out, codec, new byte[]{version, UNCHANGED_TYPE});
				return;
			}
			if (offset < 0 || offset > content.length) {
				writeFrame(out, codec, error(version, "Invalid range", ERROR_CODE_STALE_RANGE));
				return;
			}

			long remaining = content.length - offset;
			ByteBuffer header = ByteBuffer.allocate(2 + 8);
			header.put(version);
			header.put(FILE_RESPONSE_TYPE);
			header.putLong(remaining);
			writeFrame(out, codec, header.array());
			if (remaining > 0) writeFrame(out, codec, Arrays.copyOfRange(content, (int) offset, content.length));
			writeFrame(out, codec, new byte[]{version, END_OF_TRANSMISSION});
		}

		private static byte[] error(byte version, String message, byte errorCode) {
			byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
			ByteBuffer buffer = ByteBuffer.allocate(2 + 4 + messageBytes.length + 1);
			buffer.put(version);
			buffer.put(ERROR);
			buffer.putInt(messageBytes.length);
			buffer.put(messageBytes);
			buffer.put(errorCode);
			return buffer.array();
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
			executor.shutdownNow();
		}
	}
}
