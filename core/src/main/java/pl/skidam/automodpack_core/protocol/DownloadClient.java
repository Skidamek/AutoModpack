package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.IntConsumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import pl.skidam.automodpack_core.auth.DnsPinResolver;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryIndex;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.mcholepunch.HolepunchClient;
import pl.skidam.mcholepunch.HolepunchConnection;
import pl.skidam.mcholepunch.HolepunchOptions;
import pl.skidam.mcholepunch.HolepunchRoute;

public class DownloadClient implements AutoCloseable {

	public static final ExecutorService NET_EXECUTOR = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "automodpack-net");
		t.setDaemon(true);
		return t;
	});

	private static final int MAX_CONNECTIONS = 5;

	private final ConnectionJsons.ConnectionInfo connectionInfo;
	private final byte[] secretBytes;
	private final Function<X509Certificate, CompletableFuture<Boolean>> trustCallback;
	private final CustomizableTrustManager.SessionTrust sessionTrust;
	private final TransportRoute route;
	private final Object poolLock = new Object();
	private final Deque<Connection> availableConnections = new ArrayDeque<>();
	private final Deque<CompletableFuture<Connection>> connectionWaiters = new ArrayDeque<>();
	private final Set<Connection> allConnections = Collections.newSetFromMap(new IdentityHashMap<>());
	private int openingConnections;
	private boolean closed;

	private record TransportRoute(InetSocketAddress directAddress, HolepunchRoute holepunchRoute) {}

	private record TlsCandidate(SSLSocket socket, CustomizableTrustManager trustManager) {}

	private DownloadClient(ConnectionJsons.ConnectionInfo connectionInfo, byte[] secretBytes, Function<X509Certificate, CompletableFuture<Boolean>> trustCallback,
			TransportRoute route) {
		this.connectionInfo = connectionInfo;
		this.secretBytes = secretBytes == null ? null : secretBytes.clone();
		this.trustCallback = trustCallback;
		this.route = route;
		this.sessionTrust = new CustomizableTrustManager.SessionTrust(AddressHelpers.formatAddress(connectionInfo.origin), connectionInfo.expectedFingerprint);
	}

	public static CompletableFuture<DownloadClient> createAsync(ConnectionJsons.ConnectionInfo connectionInfo, byte[] secretBytes,
			Function<X509Certificate, CompletableFuture<Boolean>> trustCallback) {
		if (connectionInfo == null || !connectionInfo.isComplete())
			return CompletableFuture.failedFuture(new IllegalArgumentException("Connection origin or endpoint is missing"));

		return resolveRouteAsync(connectionInfo).thenCompose(route -> {
			DownloadClient client = new DownloadClient(connectionInfo, secretBytes, trustCallback, route);
			return client.openConnectionAsync().thenApply(connection -> {
				synchronized (client.poolLock) {
					client.allConnections.add(connection);
					client.availableConnections.add(connection);
				}
				return client;
			}).whenComplete((ignored, error) -> {
				if (error != null) client.close();
			});
		});
	}

	private static CompletableFuture<TransportRoute> resolveRouteAsync(ConnectionJsons.ConnectionInfo connectionInfo) {
		if (connectionInfo.connectionMode == ModpackConnectionMode.HOLEPUNCH) {
			return HolepunchClient.resolve(connectionInfo.endpoint.getHostString(), connectionInfo.endpoint.getPort()).toCompletableFuture()
					.thenApply(route -> new TransportRoute(null, route));
		}

		return CompletableFuture.supplyAsync(() -> {
			String host = connectionInfo.endpoint.getHostString();
			InetSocketAddress address = new InetSocketAddress(host, connectionInfo.endpoint.getPort());
			if (address.isUnresolved()) throw new CompletionException(new IOException("Failed to resolve endpoint host: " + host));
			return new TransportRoute(address, null);
		}, NET_EXECUTOR);
	}

	private CompletableFuture<Connection> openConnectionAsync() {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return openTlsCandidate();
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, NET_EXECUTOR).thenCompose(this::validateCandidate).thenApplyAsync(candidate -> {
			try {
				return openConfiguredConnection(candidate);
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, NET_EXECUTOR);
	}

	private TlsCandidate openTlsCandidate() throws IOException {
		CustomizableTrustManager trustManager;
		try {
			trustManager = new CustomizableTrustManager(sessionTrust, null);
		} catch (Exception e) {
			throw new IOException("Failed to initialize certificate trust", e);
		}
		SSLContext context = createSSLContext(trustManager);
		Socket plainSocket = connectTransport();

		try {
			plainSocket.setSoTimeout(NETWORK_TIMEOUT_MILLIS);
			if (connectionInfo.connectionMode == ModpackConnectionMode.MAGIC) performMagicHandshake(plainSocket);
			SSLSocket tlsSocket = wrapWithTls(plainSocket, context);
			if (plainSocket instanceof HolepunchSocket holepunchSocket) awaitTransportUpgrade(holepunchSocket);
			tlsSocket.setSoTimeout(0);
			return new TlsCandidate(tlsSocket, trustManager);
		} catch (IOException e) {
			closeQuietly(plainSocket);
			throw e;
		}
	}

	private void awaitTransportUpgrade(HolepunchSocket socket) throws IOException {
		try {
			socket.enableTlsTrafficCamouflage(true);
			socket.commitTransportUpgrade().toCompletableFuture().get(NETWORK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Holepunch transport upgrade interrupted", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			throw new IOException("Holepunch transport handoff failed", cause);
		} catch (TimeoutException e) {
			throw new IOException("Holepunch transport handoff timed out", e);
		}
	}

	private static HolepunchOptions holepunchOptions() {
		HolepunchOptions.Builder builder = HolepunchOptions.builder().connectTimeout(NETWORK_TIMEOUT).negotiationTimeout(NETWORK_TIMEOUT);
		LoaderManagerService.ModPlatform platform = LOADER_MANAGER.getPlatformType();
		if (platform == LoaderManagerService.ModPlatform.FORGE || platform == LoaderManagerService.ModPlatform.NEOFORGE) {
			builder.handshakeHostSuffix(HolepunchOptions.FORGE_FML3_HANDSHAKE_HOST_SUFFIX);
		}
		return builder.build();
	}

	private Socket connectTransport() throws IOException {
		if (connectionInfo.connectionMode != ModpackConnectionMode.HOLEPUNCH) {
			Socket socket = new Socket();
			socket.connect(route.directAddress(), NETWORK_TIMEOUT_MILLIS);
			return socket;
		}

		int protocolVersion = MinecraftProtocols.forVersion(MC_VERSION);

		try {
			HolepunchSocket socket = new HolepunchSocket();
			HolepunchConnection connection = HolepunchClient.connect(route.holepunchRoute(), protocolVersion, socket.handler(), holepunchOptions())
					.toCompletableFuture().get(NETWORK_TIMEOUT.plus(NETWORK_TIMEOUT).toSeconds(), TimeUnit.SECONDS);
			socket.setConnection(connection);
			return socket;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Holepunch connect interrupted", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof IOException io) throw io;
			throw new IOException("Holepunch connect failed", cause);
		} catch (Exception e) {
			throw new IOException("Holepunch connect failed", e);
		}
	}

	private void performMagicHandshake(Socket plainSocket) throws IOException {
		DataOutputStream plainOut = new DataOutputStream(new BufferedOutputStream(plainSocket.getOutputStream()));
		DataInputStream plainIn = new DataInputStream(new BufferedInputStream(plainSocket.getInputStream()));
		byte[] hostBytes = connectionInfo.endpoint.getHostString().getBytes(StandardCharsets.UTF_8);

		plainOut.writeInt(MAGIC_AMMH);
		plainOut.writeShort(hostBytes.length);
		plainOut.write(hostBytes);
		plainOut.flush();

		int handshakeResponse = plainIn.readInt();
		if (handshakeResponse != MAGIC_AMOK) throw new IOException("Invalid response from server: " + handshakeResponse);
	}

	private SSLSocket wrapWithTls(Socket plainSocket, SSLContext context) throws IOException {
		SSLSocketFactory factory = context.getSocketFactory();
		String originHost = connectionInfo.origin.getHostString();
		SSLSocket sslSocket = (SSLSocket) factory.createSocket(plainSocket, originHost, connectionInfo.endpoint.getPort(), true);
		sslSocket.setEnabledProtocols(new String[]{"TLSv1.3"});
		sslSocket.setEnabledCipherSuites(new String[]{"TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"});

		SSLParameters parameters = new SSLParameters();
		parameters.setEndpointIdentificationAlgorithm("HTTPS");
		sslSocket.setSSLParameters(parameters);

		try {
			sslSocket.startHandshake();
			return sslSocket;
		} catch (IOException e) {
			closeQuietly(sslSocket);
			throw e;
		}
	}

	private CompletableFuture<TlsCandidate> validateCandidate(TlsCandidate candidate) {
		X509Certificate certificate = candidate.trustManager().getDeferredCertificate();
		if (certificate == null) return CompletableFuture.completedFuture(candidate);

		try {
			certificate.checkValidity();
		} catch (CertificateException e) {
			return rejectCandidate(candidate, new IOException("Untrusted certificate is not valid", e));
		}

		CompletableFuture<TlsCandidate> validation = DnsPinResolver.resolvePinAsync(connectionInfo.origin.getHostString()).thenCompose(result -> {
			if (result instanceof DnsPinResolver.Authoritative authoritative) {
				try {
					String fingerprint = getFingerprint(certificate);
					if (!authoritative.fingerprint().equals(fingerprint)) {
						return rejectCandidate(candidate,
								new IOException("Certificate does not match the DNSSEC fingerprint for " + connectionInfo.origin.getHostString()));
					}
					sessionTrust.accept(certificate);
					LOGGER.info("Trusting the self-signed certificate from {} because it matches the DNSSEC fingerprint for {}",
							connectionInfo.endpoint.getHostString(), connectionInfo.origin.getHostString());
					return CompletableFuture.completedFuture(candidate);
				} catch (CertificateException e) {
					return rejectCandidate(candidate, new IOException("Failed to validate DNSSEC-pinned certificate", e));
				}
			}
			if (result instanceof DnsPinResolver.Misconfigured misconfigured) {
				return rejectCandidate(candidate, new IOException(
						"Invalid DNSSEC AutoModpack fingerprint for " + connectionInfo.origin.getHostString() + ": " + misconfigured.reason()));
			}
			return requestManualTrust(candidate, certificate);
		});
		return validation.whenComplete((ignored, error) -> {
			if (error != null) closeQuietly(candidate.socket());
		});
	}

	private CompletableFuture<TlsCandidate> requestManualTrust(TlsCandidate candidate, X509Certificate certificate) {
		if (trustCallback == null) {
			CertificateException failure = candidate.trustManager().getDeferredFailure();
			return rejectCandidate(candidate, failure == null ? new IOException("Certificate is not trusted") : failure);
		}

		CompletableFuture<Boolean> decision;
		try {
			decision = Objects.requireNonNull(trustCallback.apply(certificate), "trust callback result");
		} catch (Exception e) {
			return rejectCandidate(candidate, new IOException("Certificate trust callback failed", e));
		}

		return decision.handle((trusted, error) -> {
			if (error != null) {
				closeQuietly(candidate.socket());
				Throwable cause = unwrap(error);
				if (cause instanceof CertificateTrustCancelledException cancelled) throw new CompletionException(cancelled);
				throw new CompletionException(new IOException("Certificate trust decision failed", cause));
			}
			if (!trusted) {
				closeQuietly(candidate.socket());
				throw new CompletionException(new IOException("User rejected certificate"));
			}
			try {
				sessionTrust.accept(certificate);
				return candidate;
			} catch (CertificateException e) {
				closeQuietly(candidate.socket());
				throw new CompletionException(e);
			}
		});
	}

	private Connection openConfiguredConnection(TlsCandidate candidate) throws IOException {
		try {
			candidate.socket().setSoTimeout(NETWORK_TIMEOUT_MILLIS);
			return new Connection(candidate.socket(), secretBytes);
		} catch (IOException first) {
			closeQuietly(candidate.socket());
			if (!sessionTrust.hasAccepted()) throw first;
			LOGGER.warn("Modpack connection closed while waiting for certificate trust; reconnecting", first);
			TlsCandidate retry = openTlsCandidate();
			try {
				retry.socket().setSoTimeout(NETWORK_TIMEOUT_MILLIS);
				return new Connection(retry.socket(), secretBytes);
			} catch (IOException second) {
				closeQuietly(retry.socket());
				second.addSuppressed(first);
				throw second;
			}
		}
	}

	private static <T> CompletableFuture<T> rejectCandidate(TlsCandidate candidate, Throwable error) {
		closeQuietly(candidate.socket());
		return CompletableFuture.failedFuture(error);
	}

	private static SSLContext createSSLContext(CustomizableTrustManager trustManager) {
		try {
			SSLContext context = SSLContext.getInstance("TLSv1.3");
			context.init(null, new TrustManager[]{trustManager}, new SecureRandom());
			return context;
		} catch (NoSuchAlgorithmException | KeyManagementException e) {
			throw new RuntimeException("Failed to initialize SSLContext", e);
		}
	}

	private CompletableFuture<Connection> acquireConnection() {
		CompletableFuture<Connection> waiter = new CompletableFuture<>();
		synchronized (poolLock) {
			if (closed) return CompletableFuture.failedFuture(new IOException("Download client is closed"));
			connectionWaiters.add(waiter);
			pumpPool();
		}
		return waiter;
	}

	private void pumpPool() {
		while (!availableConnections.isEmpty()) {
			Connection connection = availableConnections.peek();
			if (connection.isActive()) break;
			availableConnections.remove();
			allConnections.remove(connection);
			closeQuietly(connection);
		}

		while (!connectionWaiters.isEmpty() && !availableConnections.isEmpty()) {
			CompletableFuture<Connection> waiter = connectionWaiters.remove();
			Connection connection = availableConnections.remove();
			waiter.complete(connection);
		}

		while (!closed && !connectionWaiters.isEmpty() && allConnections.size() + openingConnections < MAX_CONNECTIONS) {
			CompletableFuture<Connection> waiter = connectionWaiters.remove();
			openingConnections++;
			openConnectionAsync().whenComplete((connection, error) -> {
				synchronized (poolLock) {
					openingConnections--;
					if (closed) {
						if (connection != null) closeQuietly(connection);
						waiter.completeExceptionally(new IOException("Download client is closed"));
					} else if (error != null) {
						waiter.completeExceptionally(unwrap(error));
					} else {
						allConnections.add(connection);
						waiter.complete(connection);
					}
					pumpPool();
				}
			});
		}
	}

	private <T> CompletableFuture<T> withConnection(Function<Connection, CompletableFuture<T>> operation) {
		return acquireConnection().thenCompose(connection -> {
			CompletableFuture<T> future;
			try {
				future = operation.apply(connection);
			} catch (Exception e) {
				future = CompletableFuture.failedFuture(e);
			}
			return future.whenComplete((ignored, error) -> releaseConnection(connection, error == null));
		});
	}

	private void releaseConnection(Connection connection, boolean healthy) {
		synchronized (poolLock) {
			if (closed || !healthy || !connection.isActive()) {
				allConnections.remove(connection);
				availableConnections.remove(connection);
				closeQuietly(connection);
			} else {
				availableConnections.add(connection);
			}
			pumpPool();
		}
	}

	public CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback) {
		return withConnection(connection -> connection.sendDownloadFile(fileHash, destination, chunkCallback));
	}

	/** Downloads one authenticated historical catalogue advertised by the current generation index. */
	public CompletableFuture<Path> downloadHistoricalCatalogue(String stateDigest, Path destination, IntConsumer chunkCallback) {
		String requestKey = GenerationHistoryIndex.catalogueRequestKey(stateDigest);
		return downloadFile(requestKey.getBytes(StandardCharsets.UTF_8), destination, chunkCallback);
	}

	/** Copies a protocol frame into a remaining file length without truncating remaining through int. */
	static int writableFrameBytes(int frameLength, long remaining) {
		if (frameLength < 0) throw new IllegalArgumentException("frameLength must be non-negative");
		if (remaining <= 0L) return 0;
		return (int) Math.min(frameLength, remaining);
	}

	static boolean isSelfSigned(X509Certificate certificate) {
		if (certificate == null || !certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) return false;

		try {
			certificate.verify(certificate.getPublicKey());
			return true;
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	public static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
		Throwable current = throwable;
		while (current != null) {
			if (type.isInstance(current)) return type.cast(current);
			current = current.getCause();
		}
		return null;
	}

	public static Throwable unwrap(Throwable throwable) {
		Throwable current = throwable;
		while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	static void closeQuietly(AutoCloseable closeable) {
		try {
			closeable.close();
		} catch (Exception ignored) {
		}
	}

	@Override
	public void close() {
		List<Connection> connections;
		List<CompletableFuture<Connection>> waiters;
		synchronized (poolLock) {
			if (closed) return;
			closed = true;
			connections = new ArrayList<>(allConnections);
			waiters = new ArrayList<>(connectionWaiters);
			allConnections.clear();
			availableConnections.clear();
			connectionWaiters.clear();
		}

		IOException closedError = new IOException("Download client is closed");
		waiters.forEach(waiter -> waiter.completeExceptionally(closedError));
		connections.forEach(DownloadClient::closeQuietly);
	}
}

class Connection implements AutoCloseable {

	private byte protocolVersion = LATEST_SUPPORTED_PROTOCOL_VERSION;
	private CompressionType compressionType = CompressionType.ZSTD;
	private int chunkSize = DEFAULT_CHUNK_SIZE;
	private final byte[] secretBytes;
	private final SSLSocket socket;
	private final DataInputStream in;
	private final DataOutputStream out;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private CompressionCodec compressionCodec;
	private final ProtocolFrameCodec.FrameScratch frameScratch = new ProtocolFrameCodec.FrameScratch();

	public Connection(SSLSocket socket, byte[] secretBytes) throws IOException {
		if (socket == null || socket.isClosed()) throw new IOException("Server connection is closed");
		this.socket = socket;
		this.secretBytes = secretBytes;

		this.in = new DataInputStream(new BufferedInputStream(this.socket.getInputStream()));
		this.out = new DataOutputStream(new BufferedOutputStream(this.socket.getOutputStream()));

		try {
			if (!CompressionFactory.isAvailable(compressionType)) compressionType = CompressionType.GZIP;
			compressionType = sendCompressionConfig(compressionType);
			compressionCodec = CompressionFactory.createCodec(compressionType);
			chunkSize = sendChunkSizeConfig(DEFAULT_CHUNK_SIZE);
			sendEchoConfig();
		} catch (IOException e) {
			LOGGER.error("Failed to configure connection", e);
			throw e;
		}
	}

	public boolean isActive() {
		return !socket.isClosed();
	}

	private CompressionCodec getCompressionCodec() {
		return compressionCodec;
	}

	public CompletableFuture<Path> sendDownloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback) {
		if (destination == null) throw new IllegalArgumentException("Destination cannot be null");

		return CompletableFuture.supplyAsync(() -> {
			Exception exception = null;
			try {
				ByteArrayOutputStream baos = new ByteArrayOutputStream(64 + fileHash.length);
				DataOutputStream dos = new DataOutputStream(baos);
				dos.writeByte(protocolVersion);
				dos.writeByte(FILE_REQUEST_TYPE);
				dos.write(secretBytes);
				dos.writeInt(fileHash.length);
				dos.write(fileHash);

				writeProtocolMessage(baos.toByteArray());
				return readFileResponse(destination, chunkCallback);
			} catch (Exception e) {
				exception = e;
				throw new CompletionException(e);
			} finally {
				finalBlock(exception);
			}
		}, executor);
	}

	private void finalBlock(Exception exception) {
		try {
			int available;
			while ((available = in.available()) > 0) {
				in.skipBytes(available);
			}
		} catch (IOException e) {
			if (exception == null) throw new CompletionException(e);
		}
	}

	private void writeProtocolMessage(byte[] payload) throws IOException {
		ProtocolFrameCodec.write(out, getCompressionCodec(), payload, chunkSize);
	}

	private ProtocolFrameCodec.Frame readProtocolMessageFrame() throws IOException {
		return ProtocolFrameCodec.read(in, getCompressionCodec(), chunkSize, frameScratch);
	}

	private Path readFileResponse(Path destination, IntConsumer chunkCallback) throws IOException {
		ProtocolFrameCodec.Frame header = readProtocolMessageFrame();
		ByteBuffer headerWrap = ByteBuffer.wrap(header.data(), 0, header.length());

		byte version = headerWrap.get();
		byte messageType = headerWrap.get();

		if (messageType == ERROR) {
			int errLen = headerWrap.getInt();
			byte[] errBytes = new byte[errLen];
			headerWrap.get(errBytes);
			throw new IOException("Server error: " + new String(errBytes, StandardCharsets.UTF_8));
		}

		if (messageType == END_OF_TRANSMISSION) return destination;

		if (messageType != FILE_RESPONSE_TYPE) throw new IOException("Unexpected message type: " + messageType);

		long expectedFileSize = headerWrap.getLong();
		if (expectedFileSize < 0) throw new IOException("Negative file size: " + expectedFileSize);
		long receivedBytes = 0;

		try (OutputStream fos = LocalFileWriter.open(destination)) {
			while (receivedBytes < expectedFileSize) {
				ProtocolFrameCodec.Frame dataFrame = readProtocolMessageFrame();
				int toWrite = DownloadClient.writableFrameBytes(dataFrame.length(), expectedFileSize - receivedBytes);
				if (toWrite <= 0) throw new IOException("File frame did not advance the download");
				fos.write(dataFrame.data(), 0, toWrite);
				receivedBytes += toWrite;
				if (chunkCallback != null) chunkCallback.accept(toWrite);
			}
		}

		ProtocolFrameCodec.Frame eot = readProtocolMessageFrame();
		if (eot.length() < 2 || eot.data()[0] != version || eot.data()[1] != END_OF_TRANSMISSION) throw new IOException("Invalid EOT frame");
		return destination;
	}

	private CompressionType sendCompressionConfig(CompressionType desiredCompression) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		dos.writeByte(protocolVersion);
		dos.writeByte(CONFIGURATION_COMPRESSION_TYPE);
		dos.writeByte(desiredCompression.wireId());

		out.write(baos.toByteArray());
		out.flush();

		byte version = in.readByte();
		if (version >= 1 && version < protocolVersion) protocolVersion = version;

		byte type = in.readByte();
		if (type != CONFIGURATION_COMPRESSION_TYPE) throw new IOException("Unexpected response: " + type);

		CompressionType negotiated;
		try {
			negotiated = CompressionType.fromWireId(in.readByte());
		} catch (IllegalArgumentException e) {
			throw new IOException("Unsupported compression response", e);
		}
		return negotiated;
	}

	private int sendChunkSizeConfig(int desiredChunkSize) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		dos.writeByte(protocolVersion);
		dos.writeByte(CONFIGURATION_CHUNK_SIZE_TYPE);
		dos.writeInt(desiredChunkSize);

		out.write(baos.toByteArray());
		out.flush();

		byte version = in.readByte();
		if (version >= 1 && version < protocolVersion) protocolVersion = version;

		byte type = in.readByte();
		if (type != CONFIGURATION_CHUNK_SIZE_TYPE) throw new IOException("Unexpected response: " + type);

		int negotiated = in.readInt();
		if (negotiated < MIN_CHUNK_SIZE || negotiated > MAX_CHUNK_SIZE) throw new IOException("Chunk size out of bounds: " + negotiated);
		return negotiated;
	}

	private void sendEchoConfig() throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		dos.writeByte(protocolVersion);
		dos.writeByte(CONFIGURATION_ECHO_TYPE);
		out.write(baos.toByteArray());
		out.flush();
	}

	@Override
	public void close() {
		try {
			socket.close();
		} catch (Exception ignored) {
		}
		executor.shutdownNow();
	}
}
