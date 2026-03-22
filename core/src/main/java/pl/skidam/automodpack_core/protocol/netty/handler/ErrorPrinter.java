package pl.skidam.automodpack_core.protocol.netty.handler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;

import static pl.skidam.automodpack_core.Constants.LOGGER;

/**
 * A fallback error handler that keeps expected protocol mismatches out of warning-level stack traces.
 */
public class ErrorPrinter extends ChannelDuplexHandler {
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof DecoderException && cause.getCause() != null) {
            LOGGER.debug("Error occurred in connection to client at address {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
        } else {
            LOGGER.warn("Error occurred in connection to client at address {}", ctx.channel().remoteAddress(), cause);
        }
        ctx.close();
    }
}
