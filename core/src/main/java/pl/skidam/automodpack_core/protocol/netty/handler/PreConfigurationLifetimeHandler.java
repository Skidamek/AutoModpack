package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.PRE_CONFIGURATION_LIFETIME;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * The one server-side timer a pre-configuration connection ever feels. While the human decides on certificate trust
 * the socket must never be reaped for idleness, so the pre-configuration era is bounded only by this single lifetime
 * tripwire, sized far past any honest human decision. Once {@link ConfigurationHandler} reports the negotiation
 * complete the timer is cancelled for good and the configured connection is never time-limited by this handler.
 */
public final class PreConfigurationLifetimeHandler extends ChannelInboundHandlerAdapter {

	private final Duration lifetime;
	private ScheduledFuture<?> expiry;

	public PreConfigurationLifetimeHandler() {
		this(PRE_CONFIGURATION_LIFETIME);
	}

	PreConfigurationLifetimeHandler(Duration lifetime) {
		this.lifetime = lifetime;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		schedule(ctx);
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		schedule(ctx);
		ctx.fireChannelActive();
	}

	private void schedule(ChannelHandlerContext ctx) {
		if (expiry != null) return;
		expiry = ctx.executor().schedule(() -> {
			LOGGER.info("Closing the pre-configuration connection from {}: no configuration handshake within {}", ctx.channel().remoteAddress(), lifetime);
			ctx.close();
		}, lifetime.toMillis(), TimeUnit.MILLISECONDS);
	}

	/** The client finished negotiating: the connection leaves the pre-configuration era and is never reaped. */
	void configurationComplete() {
		cancel();
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
