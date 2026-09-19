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

import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.ServerHolepunchBridge;
import pl.skidam.automodpack_core.protocol.netty.handler.AmmhGateHandler;
import pl.skidam.automodpack_core.protocol.netty.handler.HttpContractHandler;
import pl.skidam.automodpack_core.protocol.netty.handler.ProxyProtocolHandler;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ModpackContentTools;

public class NettyServer {

	public static final AttributeKey<SocketAddress> REAL_REMOTE_ADDR = AttributeKey.valueOf("REAL_REMOTE_ADDR");
	private volatile TrafficShaper trafficShaper;
	private volatile Map<String, Path> paths = Map.of();
	private MultithreadEventLoopGroup eventLoopGroup;
	private ExecutorService senderExecutor;
	private ChannelFuture serverChannel;
	private volatile boolean sharedMagicEnabled;
	private volatile boolean holepunchActive;
	private String certificateFingerprint;
	private SslContext sslCtx;

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

	private final ActivityTracker activityTracker = new ActivityTracker();

	public ActivityTracker activityTracker() {
		return activityTracker;
	}

	/** The activity command's read model, with object hashes resolved against the current generation's pack paths. */
	public ActivityTracker.Snapshot activitySnapshot() {
		Map<String, String> names = new HashMap<>();
		getPath(GenerationHosting.HEAD_DOCUMENT_KEY).ifPresent(head -> {
			GenerationJsons.HeadDocumentFields document = ModpackContentTools.readHeadDocument(head);
			if (document != null) document.policy.categories.forEach((category, groups) -> groups.forEach((group, fields) -> fields.files.forEach((path, file) -> names.put(file.sha1, path))));
		});
		if (trafficShaper != null) activityTracker.writeThroughput(trafficShaper.handler().trafficCounter().lastWriteThroughput());
		return activityTracker.snapshot(names);
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

		if (getPath(GenerationHosting.HEAD_DOCUMENT_KEY).isEmpty()) {
			LOGGER.warn("No current generation record is prepared. Can't start modpack hosting.");
			return Optional.empty();
		}

		ModpackConnectionMode connectionMode = serverConfig.connectionMode;
		if (serverConfig.disableInternalTLS)
			LOGGER.info("Internal TLS termination is disabled; the listener serves plaintext and expects TLS to be terminated in front of it");

		try {
			startSenders();

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

			if (serverConfig.bindPort == -1) {
				LOGGER.info("{} is advertised without a built-in listener; expecting the endpoint to be served externally", connectionMode);
				senderExecutor.shutdownNow();
				senderExecutor = null;
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
						// A PROXY header claims a source address that feeds secret validation, so only a listener whose
						// operator opted in (a trusted proxy is in front) may consume one.
						if (serverConfig.acceptProxyProtocol) ch.pipeline().addLast("proxy-protocol", new ProxyProtocolHandler());
						// The contract listener is public, so fully silent connections are reaped: the all-idle bound sits
						// far past any client's keep-alive reuse window, and a streaming response keeps writing, so the
						// reap can never interrupt a live transfer.
						ch.pipeline().addLast(IdleStateHandler.class.getSimpleName(), new IdleStateHandler(0, 0, NetUtils.HTTP_IDLE_REAP_SECONDS));
						ch.pipeline().addLast("traffic-shaper", NettyServer.this.trafficHandler());
						if (connectionMode == ModpackConnectionMode.MAGIC) {
							ch.pipeline().addLast(MOD_ID + "-magic-gate", new AmmhGateHandler(NettyServer.this, senderExecutor, false));
							return;
						}
						installContractHandlers(ch.pipeline());
					}
				}).group(eventLoopGroup).localAddress(bindAddress).bind().syncUninterruptibly();
		return Optional.of(serverChannel);
	}

	/** TLS (when internal termination is on) and the URL contract, appended after whatever wire-side stack is present. */
	public void installContractHandlers(ChannelPipeline pipeline) {
		if (sslCtx != null) pipeline.addLast("tls", sslCtx.newHandler(pipeline.channel().alloc()));
		else LOGGER.debug("TLS termination handled externally: {}", pipeline.channel().remoteAddress());
		pipeline.addLast(MOD_ID, new HttpContractHandler(this, senderExecutor));
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

	/** The pool body-streaming workers run on: one worker per in-flight response, each holding one FileChannel and one reusable chunk buffer off the event loop. */
	public ExecutorService senderExecutor() {
		return senderExecutor;
	}

	/** Starts the body-streaming pool; every hosting shape streams bodies, so this must run before any listener or swap goes live. */
	public void startSenders() {
		senderExecutor = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "automodpack-sender");
			t.setDaemon(true);
			return t;
		});
	}
}
