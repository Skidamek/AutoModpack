package pl.skidam.automodpack_core.netty.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.netty.handler.ProtocolClientHandler;
import pl.skidam.automodpack_core.netty.handler.ProtocolMessageEncoder;
import pl.skidam.automodpack_core.netty.message.EchoMessage;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

import static pl.skidam.automodpack_core.netty.NetUtils.MAGIC_AMMC;

public class EchoClient extends NettyClient {
    private final List<Channel> channels = new ArrayList<>();
    private final EventLoopGroup group;
    private final SslContext sslCtx;
    private final EchoClient echoClient;
    private final Semaphore channelLock = new Semaphore(0);

    public EchoClient(InetSocketAddress remoteAddress) throws InterruptedException, SSLException {
        this.echoClient = this;

        // Yes, we use the insecure because server uses self-signed cert and we have different way to verify the authenticity
        // Via secret and fingerprint, so the encryption strength should be the same, correct me if I'm wrong, thanks
        sslCtx = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .sslProvider(SslProvider.JDK)
                .protocols("TLSv1.3")
                .ciphers(Arrays.asList(
                        "TLS_AES_128_GCM_SHA256",
                        "TLS_AES_256_GCM_SHA384",
                        "TLS_CHACHA20_POLY1305_SHA256"))
                .build();

        group = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ch.pipeline().addLast(new ProtocolClientHandler(echoClient));
                    }
                });

        // Initialize channels and wait for the channels in pool.
        Channel channel = bootstrap.connect(remoteAddress).sync().channel();
        ByteBuf msg = channel.alloc().buffer(4);
        msg.writeInt(MAGIC_AMMC);
        channel.writeAndFlush(msg);

        channelLock.acquire();
    }

    @Override
    public void secureInit(ChannelHandlerContext ctx) {
        ctx.pipeline().addLast(new ProtocolMessageEncoder());
    }

    @Override
    public void addChannel(Channel channel) {
        channels.add(channel);
    }

    @Override
    public void removeChannel(Channel channel) {
        channels.remove(channel);
    }

    @Override
    public void releaseChannel() {
        channelLock.release();
    }

    @Override
    public Secrets.Secret getSecret() {
        return null;
    }

    /**
     * Downloads a file by its SHA-1 hash to the specified destination.
     * Returns a CompletableFuture that completes when the download finishes.
     */
    public CompletableFuture<Void> sendEcho(byte[] secret, byte[] data) {
        Channel channel = channels.get(0);

        // Build and send the file request (which carries the secret and file hash).
        EchoMessage request = new EchoMessage((byte) 1, secret, data);
        channel.writeAndFlush(request);

        // Return the future that will complete when the download finishes.
        return null;
    }

    /**
     * Closes all channels in the pool and shuts down the event loop.
     */
    public void close() {
        for (Channel channel : channels) {
            if (channel.isOpen()) {
                channel.close();
            }
        }
        group.shutdownGracefully();
    }

    public SslContext getSslCtx() {
        return sslCtx;
    }
}
