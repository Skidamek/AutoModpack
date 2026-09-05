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
import io.netty.util.AttributeKey;

import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.ServerHolepunchBridge;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.protocol.netty.handler.ProtocolServerHandler;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.HashUtils;

public class NettyServer {

	public static final AttributeKey<SocketAddress> REAL_REMOTE_ADDR = AttributeKey.valueOf("REAL_REMOTE_ADDR");
	public static final AttributeKey<CompressionType> COMPRESSION_TYPE = AttributeKey.valueOf("COMPRESSION_TYPE");
	public static final AttributeKey<Integer> CHUNK_SIZE = AttributeKey.valueOf("CHUNK_SIZE");
	public static final AttributeKey<Byte> PROTOCOL_VERSION = AttributeKey.valueOf("PROTOCOL_VERSION");
	private final Map<Channel, String> connections = new ConcurrentHashMap<>();
	private volatile Map<String, Path> paths = Map.of();
	private MultithreadEventLoopGroup eventLoopGroup;
	private ChannelFuture serverChannel;
	private volatile boolean sharedMagicEnabled;
	private String certificateFingerprint;
	private SslContext sslCtx;

	public void addConnection(Channel channel, String secret) {
		synchronized (connections) {
			connections.put(channel, secret);
		}
	}

	public void removeConnection(Channel channel) {
		synchronized (connections) {
			connections.remove(channel);
		}
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

	public synchronized Optional<ChannelFuture> start() {
		if (isRunning()) {
			LOGGER.warn("Modpack hosting is already running");
			return Optional.ofNullable(serverChannel);
		}

		if (!serverConfig.modpackHost) {
			LOGGER.warn("Built-in modpack hosting is disabled in config");
			return Optional.empty();
		}

		if (getPath("").isEmpty()) {
			LOGGER.warn("No current generation record is prepared. Can't start modpack hosting.");
			return Optional.empty();
		}

		ModpackConnectionMode connectionMode = serverConfig.connectionMode;
		if (connectionMode == ModpackConnectionMode.DIRECT && serverConfig.bindPort == -1) {
			LOGGER.info("DIRECT is advertised without a built-in listener; expecting the endpoint to be handled externally");
			return Optional.empty();
		}

		try {
			prepareTls();

			if (connectionMode == ModpackConnectionMode.HOLEPUNCH) {
				LOGGER.info("Hosting modpack through Minecraft Login holepunch; bindPort is not used");
				ServerHolepunchBridge.register(this);
				return Optional.empty();
			}

			if (connectionMode == ModpackConnectionMode.MAGIC && serverConfig.bindPort == -1) {
				LOGGER.info("Hosting modpack through magic packet routing on the Minecraft port");
				new TrafficShaper(null);
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

		new TrafficShaper(eventLoopGroup);

		serverChannel = new ServerBootstrap().channel(socketChannelClass).childOption(ChannelOption.TCP_NODELAY, true)
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ch.pipeline().addLast(MOD_ID, new ProtocolServerHandler(NettyServer.this, connectionMode, false));
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

		TrafficShaper.close();

		try {
			if (eventLoopGroup != null) eventLoopGroup.shutdownGracefully().sync();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.error("Interrupted while stopping server event loop", e);
			stopped = false;
		} finally {
			eventLoopGroup = null;
		}

		sslCtx = null;
		certificateFingerprint = null;
		return stopped;
	}

	public boolean isRunning() {
		return sharedMagicEnabled || ServerHolepunchBridge.isRegistered() || serverChannel != null && serverChannel.channel().isOpen();
	}

	public SslContext getSslCtx() {
		return sslCtx;
	}
}
