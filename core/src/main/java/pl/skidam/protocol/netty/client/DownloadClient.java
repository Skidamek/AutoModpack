package pl.skidam.protocol.netty.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.stream.ChunkedWriteHandler;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.protocol.netty.handler.*;
import pl.skidam.protocol.netty.message.FileRequestMessage;
import pl.skidam.protocol.netty.message.RefreshRequestMessage;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static pl.skidam.automodpack_core.GlobalVariables.MOD_ID;
import static pl.skidam.protocol.NetUtils.MAGIC_AMMC;

public class DownloadClient extends NettyClient {
    private final Map<Channel, AtomicBoolean> channels = new HashMap<>(); // channel, isBusy
    private final EventLoopGroup group;
    private final SslContext sslCtx;
    private final Secrets.Secret secret;
    private final DownloadClient downloadClient;
    private final Semaphore channelLock = new Semaphore(0);

    public DownloadClient(InetSocketAddress remoteAddress, Secrets.Secret secret, int poolSize) throws InterruptedException, SSLException {
        this.downloadClient = this;
        this.secret = secret;

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
                        ch.pipeline().addLast(MOD_ID, new ProtocolClientHandler(downloadClient));
                    }
                });

        // Initialize channels and wait for the channels in pool.
        for (int i = 0; i < poolSize; i++) {
            Channel channel = bootstrap.connect(remoteAddress).sync().channel();
            ByteBuf msg = channel.alloc().buffer(4);
            msg.writeInt(MAGIC_AMMC);
            channel.writeAndFlush(msg);
        }

        channelLock.acquire(poolSize);
    }

    @Override
    public void secureInit(ChannelHandlerContext ctx) {
        ctx.pipeline().addLast("zstd-encoder", new ZstdEncoder());
        ctx.pipeline().addLast("zstd-decoder", new ZstdDecoder());
        ctx.pipeline().addLast("chunked-write", new ChunkedWriteHandler());
        ctx.pipeline().addLast("protocol-msg-decoder", new ProtocolMessageEncoder());
    }

    @Override
    public void addChannel(Channel channel) {
        channels.put(channel, new AtomicBoolean(false));
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
        return secret;
    }

    /**
     * Downloads a file by its SHA-1 hash to the specified destination.
     * Returns a CompletableFuture that completes when the download finishes.
     */
    public CompletableFuture<Object> downloadFile(byte[] fileHash, Path destination) {
        // Select first not busy channel
        Channel channel = channels.entrySet().stream()
                .filter(entry -> !entry.getValue().get())
                .findFirst()
                .map(Map.Entry::getKey)
                .orElseThrow(() -> new IllegalStateException("No available channels"));

        // Mark channel as busy
        channels.get(channel).set(true);

        // Add a new FileDownloadHandler to process this download.
        FileDownloadHandler downloadHandler = new FileDownloadHandler(destination);
        channel.pipeline().addLast("download-handler", downloadHandler);

        byte[] bsecret = Base64.getUrlDecoder().decode(secret.secret());

        // Build and send the file request (which carries the secret and file hash).
        FileRequestMessage request = new FileRequestMessage((byte) 1, bsecret, fileHash);
        channel.writeAndFlush(request);

        // Return the future that will complete when the download finishes.
        return downloadHandler.getDownloadFuture().whenComplete((result, throwable) -> {
            // Mark channel as not busy
            channels.get(channel).set(false);
        });
    }

    /**
     * Downloads a file by its SHA-1 hash to the specified destination.
     * Returns a CompletableFuture that completes when the download finishes.
     */
    public CompletableFuture<Object> requestRefresh(byte[][] fileHashes) {
        // Select first not busy channel
        Channel channel = channels.entrySet().stream()
                .filter(entry -> !entry.getValue().get())
                .findFirst()
                .map(Map.Entry::getKey)
                .orElseThrow(() -> new IllegalStateException("No available channels"));

        // Mark channel as busy
        channels.get(channel).set(true);

        // Add a new FileDownloadHandler to process this download.
        FileDownloadHandler downloadHandler = new FileDownloadHandler(null);
        channel.pipeline().addLast("download-handler", downloadHandler);

        byte[] bsecret = Base64.getUrlDecoder().decode(secret.secret());

        // Build and send the file request (which carries the secret and file hash).
        RefreshRequestMessage request = new RefreshRequestMessage((byte) 1, bsecret, fileHashes);
        channel.writeAndFlush(request);

        // Return the future that will complete when the download finishes.
        return downloadHandler.getDownloadFuture().whenComplete((result, throwable) -> {
            // Mark channel as not busy
            channels.get(channel).set(false);
        });
    }

    /**
     * Closes all channels in the pool and shuts down the event loop.
     */
    public void close() {
        for (Channel channel : channels.keySet()) {
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
