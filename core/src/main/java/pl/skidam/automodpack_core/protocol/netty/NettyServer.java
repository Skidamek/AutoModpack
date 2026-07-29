package pl.skidam.automodpack_core.protocol.netty;

import static pl.skidam.automodpack_core.Constants.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
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

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.ServerHolepunchBridge;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.protocol.netty.handler.ProtocolServerHandler;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.ObservableMap;

public class NettyServer {

	public static final AttributeKey<SocketAddress> REAL_REMOTE_ADDR = AttributeKey.valueOf("REAL_REMOTE_ADDR");
	public static final AttributeKey<CompressionType> COMPRESSION_TYPE = AttributeKey.valueOf("COMPRESSION_TYPE");
	public static final AttributeKey<Integer> CHUNK_SIZE = AttributeKey.valueOf("CHUNK_SIZE");
	public static final AttributeKey<Byte> PROTOCOL_VERSION = AttributeKey.valueOf("PROTOCOL_VERSION");
	private final Map<Channel, String> connections = new ConcurrentHashMap<>();
	private final Map<String, Path> paths = new ConcurrentHashMap<>();
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

	public void setPaths(ObservableMap<String, Path> paths) {
		this.paths.putAll(paths.getMap());
		paths.addOnPutCallback(this.paths::put);
		paths.addOnRemoveCallback(this.paths::remove);
	}

	public void removePaths(ObservableMap<String, Path> paths) {
		paths.getMap().forEach(this.paths::remove);
	}

	public Optional<Path> getPath(String hash) {
		return Optional.ofNullable(paths.get(hash));
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

		if (paths.isEmpty()) {
			LOGGER.warn("No file to host. Can't start modpack hosting.");
			return Optional.empty();
		}

		updateAdvertisedAddress();

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

			if (connectionMode == ModpackConnectionMode.MAGIC_PACKET && serverConfig.bindPort == -1) {
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

		if (!Files.exists(serverCertFile) || !Files.exists(serverPrivateKeyFile)) {
			KeyPair keyPair = NetUtils.generateKeyPair();
			X509Certificate cert = NetUtils.selfSign(keyPair);
			NetUtils.saveCertificate(cert, serverCertFile);
			NetUtils.savePrivateKey(keyPair.getPrivate(), serverPrivateKeyFile);
		}

		X509Certificate cert = NetUtils.loadCertificate(serverCertFile);
		if (cert == null) throw new IllegalStateException("Server certificate couldn't be loaded");

		sslCtx = SslContextBuilder.forServer(serverCertFile.toFile(), serverPrivateKeyFile.toFile()).sslProvider(SslProvider.JDK).protocols("TLSv1.3")
				.ciphers(Arrays.asList("TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256")).sessionTimeout(1800).build();
		certificateFingerprint = NetUtils.getFingerprint(cert);
		if (certificateFingerprint != null) LOGGER.warn("Certificate fingerprint: {}", certificateFingerprint);
	}

	private void updateAdvertisedAddress() {
		if (!serverConfig.updateIpsOnEveryStart) return;

		String publicIp = AddressHelpers.getPublicIp();
		if (publicIp != null) {
			serverConfig.advertisedEndpointHost = publicIp;
			LOGGER.warn("Setting Host IP to {}", serverConfig.advertisedEndpointHost);
		} else {
			LOGGER.error("Couldn't get public IP, please change it manually!");
		}

		try {
			ConfigTools.writeAtomic(serverConfigFile, serverConfig);
		} catch (Exception e) {
			LOGGER.error("Failed to save updated advertised endpoint", e);
		}
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
