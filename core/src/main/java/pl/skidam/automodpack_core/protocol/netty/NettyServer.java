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
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.*;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class NettyServer {
    public static final AttributeKey<Boolean> USE_COMPRESSION = AttributeKey.valueOf("useCompression");
    private final Map<Channel, String> connections = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Path> paths = Collections.synchronizedMap(new HashMap<>());
    private ChannelFuture serverChannel;
    private Boolean shouldHost = false; // needed for stop modpack hosting for minecraft port
    private X509Certificate certificate;
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
        try {
            X509Certificate cert;
            PrivateKey key;

            if (!Files.exists(serverCertFile) || !Files.exists(serverPrivateKeyFile)) {
                // Create a self-signed certificate
                KeyPair keyPair = NetUtils.generateKeyPair();
                cert = NetUtils.selfSign(keyPair);
                key = keyPair.getPrivate();

                // save it to the file
                NetUtils.saveCertificate(cert, serverCertFile);
                NetUtils.savePrivateKey(keyPair.getPrivate(), serverPrivateKeyFile);
            } else {
                cert = NetUtils.loadCertificate(serverCertFile);
                key = NetUtils.loadPrivateKey(serverPrivateKeyFile);
            }

            if (cert == null || key == null) {
                throw new IllegalStateException("Failed to load certificate or private key");
            }

            // Shiny TLS 1.3
            certificate = cert;
            sslCtx = SslContextBuilder.forServer(key, cert)
                    .sslProvider(SslProvider.JDK)
                    .protocols("TLSv1.3")
                    .ciphers(Arrays.asList(
                            "TLS_AES_128_GCM_SHA256",
                            "TLS_AES_256_GCM_SHA384",
                            "TLS_CHACHA20_POLY1305_SHA256"))
                    .build();

            if (!canStart()) {
                return Optional.empty();
            }

            int port = serverConfig.hostPort;
            InetSocketAddress bindAddress = new InetSocketAddress("0.0.0.0", port);
            LOGGER.info("Starting modpack host server on {}", bindAddress);

            Class<? extends ServerChannel> socketChannelClass;
            MultithreadEventLoopGroup eventLoopGroup;
            if (Epoll.isAvailable()) {
                socketChannelClass = EpollServerSocketChannel.class;
                eventLoopGroup = new EpollEventLoopGroup(new CustomThreadFactoryBuilder().setNameFormat("AutoModpack Epoll Server IO #%d").setDaemon(true).build());
            } else {
                socketChannelClass = NioServerSocketChannel.class;
                eventLoopGroup = new NioEventLoopGroup(new CustomThreadFactoryBuilder().setNameFormat("AutoModpack Server IO #%d").setDaemon(true).build());
            }

            serverChannel = new ServerBootstrap()
                    .channel(socketChannelClass)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
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
        if (serverChannel == null) {
            if (shouldHost) {
                shouldHost = false;
                return true;
            }
            return false;
        }

        try {
            serverChannel.channel().close().sync();
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

    public X509Certificate getCert() {
        return certificate;
    }

    public SslContext getSslCtx() {
        return sslCtx;
    }

    private boolean canStart() {
        if (isRunning()) {
            return false;
        }

        if (!serverConfig.modpackHost) {
            LOGGER.warn("Modpack hosting is disabled in config");
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
