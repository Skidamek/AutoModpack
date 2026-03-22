package pl.skidam.automodpack_core.protocol.netty;

import static pl.skidam.automodpack_core.Constants.*;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import pl.skidam.automodpack_core.protocol.HostConnectionStats;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.protocol.netty.handler.ProtocolServerHandler;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.ObservableMap;

public class NettyServer {

    private final Map<String, Path> paths = new java.util.concurrent.ConcurrentHashMap<>();
    private MultithreadEventLoopGroup eventLoopGroup;
    private ChannelFuture serverChannel;
    private Boolean shouldHost = false; // needed for stop modpack hosting for minecraft port
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

    public Optional<ChannelFuture> startServer() {
        if (!serverConfig.modpackHost) {
            LOGGER.warn("Modpack hosting is disabled in config");
            return Optional.empty();
        }

        try {
            if (!canStart()) {
                new TrafficShaper(null);
                return Optional.empty();
            }

            String address = serverConfig.bindAddress;
            int port = serverConfig.bindPort;
            InetSocketAddress bindAddress = null;
            if (port != -1) {
                if (address == null || address.isBlank()) {
                    bindAddress = new InetSocketAddress(port);
                } else {
                    bindAddress = new InetSocketAddress(address, port);
                }
            }

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
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(MOD_ID, new ProtocolServerHandler());
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

    public boolean start() {
        return startServer().isPresent() || shouldHost;
    }

    public boolean shouldHost() {
        return shouldHost;
    }

    // Returns true if stopped successfully
    public boolean stop() {
        try {
            if (serverChannel != null) {
                serverChannel.channel().close().sync();
                serverChannel = null;
            }

            shouldHost = false;

            TrafficShaper.close();

            if (eventLoopGroup != null) {
                eventLoopGroup.shutdownGracefully().sync();
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

    public Map<String, Integer> getConnectionCountsByEndpoint() {
        return HostConnectionStats.countValues(java.util.List.of());
    }

    private boolean canStart() {
        if (isRunning() || !serverConfig.modpackHost) {
            return false;
        }

        if (paths.isEmpty()) {
            LOGGER.warn("No file to host. Can't start modpack host server.");
            return false;
        }

        if (serverConfig.updateIpsOnEveryStart) {
            String publicIp = AddressHelpers.getPublicIp();
            if (publicIp != null) {
                serverConfig.addressToSend = publicIp;
                LOGGER.warn("Setting Host IP to {}", serverConfig.addressToSend);
            } else {
                LOGGER.error("Couldn't get public IP, please change it manually! ");
            }

            try {
                ConfigTools.save(serverConfigFile, serverConfig);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        shouldHost = true; // At this point we know that we want to host the modpack

        if (serverConfig.bindPort == -1) {
            LOGGER.info("Hosting modpack on Minecraft port");
            return false; // Dont start separate server for modpack hosting, use minecraft port instead
        } else {
            return true; // Start separate server for modpack hosting
        }
    }
}
