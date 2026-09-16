package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * The one time policy a server-side modpack connection ever feels, in three eras. Pre-configuration: a fixed lifetime
 * tripwire sized far past any honest human certificate-trust decision, because a client which died at the screen must
 * not pin the socket. Configured: a short fixed deadline, because authentication is machine-speed - every honest
 * client sends its secret in its first protocol message, and any byte sent after configuration either authenticates
 * or closes the connection, so this deadline cannot be stretched. Authenticated: a generous all-idle bound - transfers
 * and requests reset it continuously and an honest human pause fits inside it with room to spare, while a zombie
 * holding a revoked secret stops pinning a socket within it.
 */
public final class ConnectionLifetimeHandler extends ChannelInboundHandlerAdapter {

	private final Duration lifetime;
	private final Duration unauthenticatedLifetime;
	private ScheduledFuture<?> expiry;
	private ChannelHandlerContext ctx;
	private boolean configured;
	private boolean trustEstablished;

	public ConnectionLifetimeHandler() {
		this(PRE_CONFIGURATION_LIFETIME);
	}

	ConnectionLifetimeHandler(Duration lifetime) {
		this(lifetime, UNAUTHENTICATED_LIFETIME);
	}

	ConnectionLifetimeHandler(Duration lifetime, Duration unauthenticatedLifetime) {
		this.lifetime = lifetime;
		this.unauthenticatedLifetime = unauthenticatedLifetime;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		this.ctx = ctx;
		schedulePreConfiguration();
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		this.ctx = ctx;
		schedulePreConfiguration();
		ctx.fireChannelActive();
	}

	private void schedulePreConfiguration() {
		if (expiry != null || configured) return;
		expiry = ctx.executor().schedule(() -> {
			LOGGER.info("Closing the pre-configuration connection from {}: no configuration handshake within {}", ctx.channel().remoteAddress(), lifetime);
			ctx.close();
		}, lifetime.toMillis(), TimeUnit.MILLISECONDS);
	}

	/** The client finished negotiating: the pre-configuration era ends and the unauthenticated deadline takes over. */
	void configurationComplete(ChannelHandlerContext pipelineCtx) {
		if (trustEstablished || configured) return;
		configured = true;
		cancel();
		expiry = pipelineCtx.executor().schedule(() -> {
			LOGGER.info("Closing the configured connection from {}: no authenticated request within {}", pipelineCtx.channel().remoteAddress(), unauthenticatedLifetime);
			pipelineCtx.close();
		}, unauthenticatedLifetime.toMillis(), TimeUnit.MILLISECONDS);
	}

	/** The first validated secret: the connection is trusted, only idleness can end it from here. */
	void authenticated(ChannelHandlerContext pipelineCtx) {
		authenticated(pipelineCtx, AUTHENTICATED_IDLE_TIMEOUT);
	}

	void authenticated(ChannelHandlerContext pipelineCtx, Duration idleTimeout) {
		if (trustEstablished) return;
		trustEstablished = true;
		cancel();
		pipelineCtx.pipeline().addFirst(new IdleStateHandler(0, 0, Math.toIntExact(idleTimeout.toSeconds())));
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.ALL_IDLE) {
			LOGGER.info("Closing the authenticated connection from {}: idle for {}", ctx.channel().remoteAddress(), AUTHENTICATED_IDLE_TIMEOUT);
			ctx.close();
			return;
		}
		super.userEventTriggered(ctx, evt);
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		cancel();
	}

	private void cancel() {
		ScheduledFuture<?> scheduled = expiry;
		if (scheduled != null) scheduled.cancel(false);
	}
}
