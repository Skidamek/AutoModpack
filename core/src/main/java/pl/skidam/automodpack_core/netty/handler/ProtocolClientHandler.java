package pl.skidam.automodpack_core.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.SslHandler;
import pl.skidam.automodpack_core.netty.NetUtils;
import pl.skidam.automodpack_core.netty.client.NettyClient;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

import static pl.skidam.automodpack_core.netty.NetUtils.MAGIC_AMOK;

public class ProtocolClientHandler extends ByteToMessageDecoder {

    private final NettyClient client;

    public ProtocolClientHandler(NettyClient client) {
        this.client = client;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        try {
            if (in.readableBytes() < 4) {
                return;
            }

            int magic = in.getInt(0);
            if (magic != MAGIC_AMOK) {
                client.releaseChannel();
            } else {
                // Consume the packet
                in.skipBytes(in.readableBytes());

                // Set up the pipeline for the protocol
                SslHandler sslHandler = client.getSslCtx().newHandler(ctx.alloc());
                ctx.pipeline().addLast("tls", sslHandler);

                // Wait for SSL handshake to complete before sending data
                sslHandler.handshakeFuture().addListener(future -> {
                    if (!future.isSuccess()) {
                        ctx.close();
                        System.err.println("SSL handshake failed");
                        return;
                    }

                    try {
                        Certificate[] certs = sslHandler.engine().getSession().getPeerCertificates();
                        if (certs == null || certs.length == 0 || certs.length > 3) {
                            return;
                        }

                        for (Certificate cert : certs) {
                            if (cert instanceof X509Certificate x509Cert) {
                                String fingerprint = NetUtils.getFingerprint(x509Cert, client.getSecret().secret());
                                if (fingerprint.equals(client.getSecret().fingerprint())) {
                                    System.out.println("Server certificate verified, fingerprint: " + fingerprint);
                                    client.secureInit(ctx);
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        ctx.close();
                    } finally {
                        if (ctx.channel().isOpen()) {
                            client.addChannel(ctx.channel());
                        }
                        client.releaseChannel();
                    }
                });
            }

            ctx.pipeline().remove(this); // Always remove this handler after processing
        } catch (Exception e) {
            e.printStackTrace();
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        client.removeChannel(ctx.channel());
        client.releaseChannel();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}