package pl.skidam.automodpack_core.protocol.netty;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.storage.StoragePaths.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.AttributeKey;

import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.ServerHolepunchBridge;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.protocol.netty.handler.ConnectionLifetimeHandler;
import pl.skidam.automodpack_core.protocol.netty.handler.HttpContractHandler;
import pl.skidam.automodpack_core.protocol.netty.handler.ProtocolServerHandler;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.HashUtils;

public class NettyServer {

	public static final AttributeKey<SocketAddress> REAL_REMOTE_ADDR = AttributeKey.valueOf("REAL_REMOTE_ADDR");
	public static final AttributeKey<CompressionCodec> COMPRESSION_CODEC = AttributeKey.valueOf("COMPRESSION_CODEC");
	public static final AttributeKey<Integer> CHUNK_SIZE = AttributeKey.valueOf("CHUNK_SIZE");
	public static final AttributeKey<Byte> PROTOCOL_VERSION = AttributeKey.valueOf("PROTOCOL_VERSION");
	private final Map<Channel, String> connections = new ConcurrentHashMap<>();
	private volatile TrafficShaper trafficShaper;
	private volatile Map<String, Path> paths = Map.of();
	private MultithreadEventLoopGroup eventLoopGroup;
	private ExecutorService senderExecutor;
	private ChannelFuture serverChannel;
	private volatile boolean sharedMagicEnabled;
	private volatile boolean holepunchActive;
	private String certificateFingerprint;
	private SslContext sslCtx;

	// The map is already a concurrent one and every access is a single atomic operation, so no external
	// lock adds anything - readers get the live map and see per-entry updates immediately.

	public static void setCompression(Channel channel, CompressionType type) {
		channel.attr(COMPRESSION_CODEC).set(CompressionFactory.createCodec(type));
	}

	public static CompressionCodec compressionCodec(Channel channel) {
		CompressionCodec codec = channel.attr(COMPRESSION_CODEC).get();
		if (codec == null) throw new IllegalStateException("Compression codec has not been configured");
		return codec;
	}

	public GlobalTrafficShapingHandler trafficHandler() {
		TrafficShaper shaper = trafficShaper;
		if (shaper == null) throw new IllegalStateException("Traffic shaper is not running");
		return shaper.handler();
	}

	public void startSharedTraffic() {
		closeTraffic();
		trafficShaper = TrafficShaper.owned();
	}

	private void startEventLoopTraffic() {
		closeTraffic();
		trafficShaper = TrafficShaper.on(eventLoopGroup);
	}

	private void closeTraffic() {
		if (trafficShaper != null) {
			trafficShaper.close();
			trafficShaper = null;
		}
	}

	public void addConnection(Channel channel, String secret) {
		connections.put(channel, secret);
	}

	public void removeConnection(Channel channel) {
		connections.remove(channel);
	}

	public Map<Channel, String> getConnections() {
		return connections;
	}

	public String getCertificateFingerprint() {
		return certificateFingerprint;
	}

	public void replacePaths(Map<String, Path> paths) {
		replacePaths(new GenerationHosting(paths));
	}

	public void replacePaths(GenerationHosting hosting) {
		this.paths = hosting.asMap();
	}

	public Optional<Path> getPath(String requestKey) {
		if (requestKey == null) return Optional.empty();
		if (requestKey.equals(GenerationHosting.HEAD_DOCUMENT_KEY) || requestKey.equals(GenerationHosting.JOURNAL_KEY)) return regularPath(paths.get(requestKey));
		if (!HashUtils.isSha1(requestKey)) return Optional.empty();

		return regularPath(paths.get(HashUtils.normalizeSha1(requestKey)));
	}

	private static Optional<Path> regularPath(Path path) {
		return path != null && !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? Optional.of(path) : Optional.empty();
	}

	/**
	 * The idle reap for public contract connections, in seconds of no reads and no writes. It sits far past any
	 * client's keep-alive reuse window while staying inside a minute-scale patience for silent sockets; a streaming
	 * response writes continuously, so the reap can never interrupt a live transfer.
	 */
	public static final int HTTP_IDLE_REAP_SECONDS = 60;

	public synchronized Optional<ChannelFuture> start() {
		if (isRunning()) {
			LOGGER.warn("Modpack hosting is already running");
			return Optional.ofNullable(serverChannel);
		}

		if (!serverConfig.modpackHost) {
			LOGGER.warn("Built-in modpack hosting is disabled in config");
			return Optional.empty();
		}

		if (getPath(GenerationHosting.HEAD_DOCUMENT_KEY).isEmpty()) {
			LOGGER.warn("No current generation record is prepared. Can't start modpack hosting.");
			return Optional.empty();
		}

		ModpackConnectionMode connectionMode = serverConfig.connectionMode;
		if (connectionMode == ModpackConnectionMode.DIRECT && serverConfig.bindPort == -1) {
			LOGGER.info("DIRECT is advertised without a built-in listener; expecting the endpoint to be handled externally");
			return Optional.empty();
		}

		if (connectionMode == ModpackConnectionMode.HTTP) {
			// HTTP is HTTPS-only by design: the embedded listener terminates TLS itself, so without it there is no
			// protocol left to serve. Advertising-only stays supported through an external static host of the contract.
			if (serverConfig.disableInternalTLS) {
				LOGGER.error("HTTP requires the built-in TLS termination; disableInternalTLS leaves the listener with no protocol it can serve");
				return Optional.empty();
			}
			if (serverConfig.bindPort == -1) {
				LOGGER.info("HTTP is advertised without a built-in listener; expecting the contract to be served externally");
				return Optional.empty();
			}
		}

		try {
			senderExecutor = Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "automodpack-sender");
				t.setDaemon(true);
				return t;
			});

			prepareTls();

			if (connectionMode == ModpackConnectionMode.HOLEPUNCH) {
				LOGGER.info("Hosting modpack through Minecraft Login holepunch; bindPort is not used");
				startSharedTraffic();
				holepunchActive = ServerHolepunchBridge.register(this);
				return Optional.empty();
			}

			if (connectionMode == ModpackConnectionMode.MAGIC && serverConfig.bindPort == -1) {
				LOGGER.info("Hosting modpack through magic packet routing on the Minecraft port");
				startSharedTraffic();
				sharedMagicEnabled = true;
				return Optional.empty();
			}

			return startDedicated(connectionMode);
		} catch (Exception e) {
			LOGGER.error("Failed to start modpack hosting", e);
			stop();
			return Optional.empty();
		}
	}

	private Optional<ChannelFuture> startDedicated(ModpackConnectionMode connectionMode) {
		String address = serverConfig.bindAddress;
		int port = serverConfig.bindPort;
		InetSocketAddress bindAddress;
		if (address == null || address.isBlank()) {
			bindAddress = new InetSocketAddress(port);
		} else {
			bindAddress = new InetSocketAddress(address, port);
		}

		LOGGER.info("Starting {} modpack host server on {}", connectionMode, bindAddress);

		Class<? extends ServerChannel> socketChannelClass;
		if (Epoll.isAvailable()) {
			socketChannelClass = EpollServerSocketChannel.class;
			eventLoopGroup = new EpollEventLoopGroup(new CustomThreadFactoryBuilder().setNameFormat("AutoModpack Epoll Server IO #%d").setDaemon(true).build());
		} else {
			socketChannelClass = NioServerSocketChannel.class;
			eventLoopGroup = new NioEventLoopGroup(new CustomThreadFactoryBuilder().setNameFormat("AutoModpack Server IO #%d").setDaemon(true).build());
		}

		startEventLoopTraffic();

		serverChannel = new ServerBootstrap().channel(socketChannelClass).childOption(ChannelOption.TCP_NODELAY, true)
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						if (connectionMode == ModpackConnectionMode.HTTP) {
							// No ConnectionLifetimeHandler and no authentication handshake in HTTP: the lifetime timer's
							// pre-configuration bound would kill a long download, and the TLS handshake is the whole session.
							// TLS is never optional here, so a cleartext request dies as an invalid TLS record and the socket closes.
							ch.pipeline().addLast("traffic-shaper", NettyServer.this.trafficHandler());
							ch.pipeline().addLast("tls", NettyServer.this.getSslCtx().newHandler(ch.alloc()));
							// The contract listener is public, so fully silent connections are reaped: the all-idle bound sits
							// far past any client's keep-alive reuse window, and a streaming response keeps writing, so the
							// reap can never interrupt a live transfer.
							ch.pipeline().addLast(IdleStateHandler.class.getSimpleName(), new IdleStateHandler(0, 0, HTTP_IDLE_REAP_SECONDS));
							ch.pipeline().addLast(MOD_ID, new HttpContractHandler(NettyServer.this, senderExecutor));
							return;
						}
						// Nothing vanilla owns this socket, so the connection lifetime timer must exist from
						// the first accepted byte: a connection that never sends its magic cannot pin the listener.
						ch.pipeline().addLast(MOD_ID + "-connection-lifetime", new ConnectionLifetimeHandler());
						ch.pipeline().addLast(MOD_ID, new ProtocolServerHandler(NettyServer.this, connectionMode, false, serverConfig.acceptProxyProtocol));
					}
				}).group(eventLoopGroup).localAddress(bindAddress).bind().syncUninterruptibly();
		return Optional.of(serverChannel);
	}

	private void prepareTls() throws Exception {
		sslCtx = null;
		certificateFingerprint = null;

		if (serverConfig.disableInternalTLS) {
			LOGGER.warn("Internal TLS termination is disabled. Clients still use TLS; traffic must be decrypted before it reaches AutoModpack.");
			return;
		}

		if (!Files.exists(SERVER_CERT_FILE) || !Files.exists(SERVER_PRIVATE_KEY_FILE)) {
			KeyPair keyPair = NetUtils.generateKeyPair();
			X509Certificate cert = NetUtils.selfSign(keyPair);
			NetUtils.saveCertificate(cert, SERVER_CERT_FILE);
			NetUtils.savePrivateKey(keyPair.getPrivate(), SERVER_PRIVATE_KEY_FILE);
		}

		X509Certificate cert = NetUtils.loadCertificate(SERVER_CERT_FILE);
		if (cert == null) throw new IllegalStateException("Server certificate couldn't be loaded");

		sslCtx = SslContextBuilder.forServer(SERVER_CERT_FILE.toFile(), SERVER_PRIVATE_KEY_FILE.toFile()).sslProvider(SslProvider.JDK).protocols("TLSv1.3")
				.ciphers(Arrays.asList("TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256")).sessionTimeout(1800).build();
		certificateFingerprint = NetUtils.getFingerprint(cert);
		if (certificateFingerprint != null) LOGGER.warn("Certificate fingerprint: {}", certificateFingerprint);
	}

	public boolean isSharedMagicEnabled() {
		return sharedMagicEnabled;
	}

	public synchronized boolean stop() {
		boolean stopped = true;
		sharedMagicEnabled = false;
		holepunchActive = false;
		ServerHolepunchBridge.close();

		try {
			if (serverChannel != null) serverChannel.channel().close().sync();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.error("Interrupted while closing server channel", e);
			stopped = false;
		} finally {
			serverChannel = null;
		}

		closeTraffic();

		try {
			if (eventLoopGroup != null) eventLoopGroup.shutdownGracefully().sync();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.error("Interrupted while stopping server event loop", e);
			stopped = false;
		} finally {
			eventLoopGroup = null;
		}

		if (senderExecutor != null) senderExecutor.shutdownNow();
		senderExecutor = null;

		sslCtx = null;
		certificateFingerprint = null;
		return stopped;
	}

	public boolean isRunning() {
		return sharedMagicEnabled || holepunchActive || serverChannel != null && serverChannel.channel().isOpen();
	}

	public SslContext getSslCtx() {
		return sslCtx;
	}

	/** The pool file-send workers run on: one worker per in-flight transfer, each holding one FileChannel and one reusable chunk buffer off the event loop. */
	public ExecutorService senderExecutor() {
		return senderExecutor;
	}
}
