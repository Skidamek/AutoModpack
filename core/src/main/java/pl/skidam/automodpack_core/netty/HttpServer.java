package pl.skidam.automodpack_core.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.Ip;
import pl.skidam.automodpack_core.utils.ObservableMap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class HttpServer {
    private final Map<String, Path> paths = Collections.synchronizedMap(new HashMap<>());
    private ChannelFuture serverChannel;

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

    public Optional<ChannelFuture> start() throws IOException {
        if (!canStart()) {
            return Optional.empty();
        }

        int port = serverConfig.hostPort;
        InetAddress address = new InetSocketAddress(port).getAddress();

        MultithreadEventLoopGroup eventLoopGroup;
        Class<? extends ServerChannel> socketChannelClass;
        if (Epoll.isAvailable()) {
            socketChannelClass =  EpollServerSocketChannel.class;
            eventLoopGroup = new EpollEventLoopGroup(new CustomThreadFactoryBuilder().setNameFormat("AutoModpack Epoll Server IO #%d").setDaemon(true).build());
        } else {
            socketChannelClass = NioServerSocketChannel.class;
            eventLoopGroup = new NioEventLoopGroup(new CustomThreadFactoryBuilder().setNameFormat("AutoModpack Server IO #%d").setDaemon(true).build());
        }

        serverChannel = new ServerBootstrap()
                .channel(socketChannelClass)
                .childHandler(
                        new ChannelInitializer<>() {
                            @Override
                            protected void initChannel(Channel channel) {
                                try {
                                    channel.config().setOption(ChannelOption.TCP_NODELAY, true);
                                } catch (Exception ignored) {
                                    // ignore it
                                }

                                channel.pipeline().addLast("automodpack_http", new HttpServerHandler());
                            }
                        }
                )
                .group(eventLoopGroup)
                .localAddress(address, port)
                .bind()
                .syncUninterruptibly();

        return Optional.ofNullable(serverChannel);
    }

    public void stop() {
        if (serverChannel == null) {
            return;
        }

        try {
            serverChannel.channel().close().sync();
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted server channel", e);
        }
    }

    public boolean isRunning() {
        // TODO Change that
        // good enough for now
        if (serverConfig.hostModpackOnMinecraftPort && serverConfig.modpackHost && !paths.isEmpty()) {
            return true;
        }

        if (serverChannel == null) {
            return false;
        }

        return serverChannel.channel().isOpen();
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
            LOGGER.info("Hosting modpack on Minecraft port");
            return false;
        }

        if (serverConfig.updateIpsOnEveryStart || (serverConfig.hostIp == null || serverConfig.hostIp.isEmpty())) {
            String publicIp = Ip.getPublic();
            if (publicIp != null) {
                serverConfig.hostIp = publicIp;
                ConfigTools.saveConfig(serverConfigFile, serverConfig);
                LOGGER.warn("Setting Host IP to {}", serverConfig.hostIp);
            } else {
                LOGGER.error("Host IP isn't set in config, please change it manually! Couldn't get public IP");
                return false;
            }
        }

        if (serverConfig.updateIpsOnEveryStart || (serverConfig.hostLocalIp == null || serverConfig.hostLocalIp.isEmpty())) {
            try {
                serverConfig.hostLocalIp = Ip.getLocal();
                ConfigTools.saveConfig(serverConfigFile, serverConfig);
                LOGGER.warn("Setting Host local IP to {}", serverConfig.hostLocalIp);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return true;
    }
}
