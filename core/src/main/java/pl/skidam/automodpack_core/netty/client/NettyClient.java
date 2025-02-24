package pl.skidam.automodpack_core.netty.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SslContext;
import pl.skidam.automodpack_core.auth.Secrets;

public abstract class NettyClient {
    public abstract SslContext getSslCtx();
    public abstract void secureInit(ChannelHandlerContext ctx);
    public abstract void addChannel(Channel channel);
    public abstract void removeChannel(Channel channel);
    public abstract void releaseChannel();
    public abstract Secrets.Secret getSecret();
}
