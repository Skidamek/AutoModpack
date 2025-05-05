package pl.skidam.automodpack_core.protocol.netty;

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
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.netty.handler.ProtocolServerHandler;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.ObservableMap;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.*;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class NettyServer {
    public static final AttributeKey<Boolean> USE_COMPRESSION = AttributeKey.valueOf("useCompression");
    private final Map<Channel, String> connections = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Path> paths = Collections.synchronizedMap(new HashMap<>());
    private MultithreadEventLoopGroup eventLoopGroup;
    private ChannelFuture serverChannel;
    private Boolean shouldHost = false; // needed for stop modpack hosting for minecraft port
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

    public void addPaths(ObservableMap<String, Path> paths) {
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

    public Optional<ChannelFuture> start() {
        if (!serverConfig.modpackHost) {
            LOGGER.warn("Modpack hosting is disabled in config");
            return Optional.empty();
        }

        try {
            if (!Files.exists(serverCertFile) || !Files.exists(serverPrivateKeyFile)) {
                // Create a self-signed certificate
                KeyPair keyPair = NetUtils.generateKeyPair();
                X509Certificate cert = NetUtils.selfSign(keyPair);

                // save it to the file
                NetUtils.saveCertificate(cert, serverCertFile);
                NetUtils.savePrivateKey(keyPair.getPrivate(), serverPrivateKeyFile);
            }

            X509Certificate cert = NetUtils.loadCertificate(serverCertFile);

            if (cert == null) {
                throw new IllegalStateException("Server certificate couldn't be loaded");
            }

            // Shiny TLS 1.3
            sslCtx = SslContextBuilder.forServer(serverCertFile.toFile(), serverPrivateKeyFile.toFile())
                    .sslProvider(SslProvider.JDK)
                    .protocols("TLSv1.3")
                    .ciphers(Arrays.asList(
                            "TLS_AES_128_GCM_SHA256",
                            "TLS_AES_256_GCM_SHA384",
                            "TLS_CHACHA20_POLY1305_SHA256"))
                    .build();

            // generate sha256 from cert as a fingerprint
            certificateFingerprint = NetUtils.getFingerprint(cert);
            LOGGER.warn("Certificate fingerprint for client validation: {}", certificateFingerprint);

            if (!canStart()) {
                new TrafficShaper(null);
                return Optional.empty();
            }

            int port = serverConfig.hostPort;
            InetSocketAddress bindAddress = new InetSocketAddress("0.0.0.0", port);
            LOGGER.info("Starting modpack host server on {}", bindAddress);

            Class<? extends ServerChannel> socketChannelClass;
            if (Epoll.isAvailable()) {
                socketChannelClass = EpollServerSocketChannel.class;
                eventLoopGroup = new EpollEventLoopGroup(new CustomThreadFactoryBuilder().setNameFormat("AutoModpack Epoll Server IO #%d").setDaemon(true).build());
            } else {
                socketChannelClass = NioServerSocketChannel.class;
                eventLoopGroup = new NioEventLoopGroup(new CustomThreadFactoryBuilder().setNameFormat("AutoModpack Server IO #%d").setDaemon(true).build());
            }

            new TrafficShaper(eventLoopGroup);

            serverChannel = new ServerBootstrap()
                    .channel(socketChannelClass)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(MOD_ID, new ProtocolServerHandler(sslCtx));
                        }
                    })
                    .group(eventLoopGroup)
                    .localAddress(bindAddress)
                    .bind()
                    .syncUninterruptibly();
        } catch (Exception e) {
            LOGGER.error("Failed to start Netty server", e);
            return Optional.empty();
        }

        return Optional.ofNullable(serverChannel);
    }

    public boolean shouldHost() {
        return shouldHost;
    }

    // Returns true if stopped successfully
    public boolean stop() {
        try {
            if (serverChannel == null) {
                if (shouldHost) {
                    shouldHost = false;
                }
            } else {
                serverChannel.channel().close().sync();
            }

            TrafficShaper.close();

            if (eventLoopGroup != null) {
                eventLoopGroup.close();
            }
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted server channel", e);
            return false;
        }

        return true;
    }

    public boolean isRunning() {
        if (serverChannel == null) {
            return shouldHost;
        }

        return serverChannel.channel().isOpen();
    }

    public SslContext getSslCtx() {
        return sslCtx;
    }

    private boolean canStart() {
        if (isRunning() || !serverConfig.modpackHost) {
            return false;
        }

        if (paths.isEmpty()) {
            LOGGER.warn("No file to host. Can't start modpack host server.");
            return false;
        }

        if (serverConfig.hostModpackOnMinecraftPort) {
            shouldHost = true;
            LOGGER.info("Hosting modpack on Minecraft port");
            return false;
        }

        if (serverConfig.updateIpsOnEveryStart || (serverConfig.hostIp == null || serverConfig.hostIp.isEmpty())) {
            String publicIp = AddressHelpers.getPublicIp();
            if (publicIp != null) {
                serverConfig.hostIp = publicIp;
                ConfigTools.save(serverConfigFile, serverConfig);
                LOGGER.warn("Setting Host IP to {}", serverConfig.hostIp);
            } else {
                LOGGER.error("Host IP isn't set in config, please change it manually! Couldn't get public IP");
                return false;
            }
        }

        if (serverConfig.updateIpsOnEveryStart || (serverConfig.hostLocalIp == null || serverConfig.hostLocalIp.isEmpty())) {
            try {
                serverConfig.hostLocalIp = AddressHelpers.getLocalIp();
                ConfigTools.save(serverConfigFile, serverConfig);
                LOGGER.warn("Setting Host local IP to {}", serverConfig.hostLocalIp);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        shouldHost = true;
        return true;
    }
}
