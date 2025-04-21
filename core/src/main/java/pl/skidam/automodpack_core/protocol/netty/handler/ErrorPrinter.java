package pl.skidam.automodpack_core.protocol.netty.handler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;

import javax.net.ssl.SSLHandshakeException;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

/**
 * A fallback error handler that logs caught exceptions. In order to reduce verbosity, TLS handshake errors are printed
 * without stack traces.
 */
public class ErrorPrinter extends ChannelDuplexHandler {
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof DecoderException && cause.getCause() != null && cause.getCause() instanceof SSLHandshakeException) {
            // Probably the client rejecting the server certificate. Omit stack trace to reduce log output.
            LOGGER.info("Error occurred in connection to client at address {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
        } else {
            // Unusual error. A stack trace might be useful.
            LOGGER.warn("Error occurred in connection to client at address {}", ctx.channel().remoteAddress(), cause);
        }
        ctx.close();
    }
}
