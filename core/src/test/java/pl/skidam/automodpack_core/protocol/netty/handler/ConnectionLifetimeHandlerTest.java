package pl.skidam.automodpack_core.protocol.netty.handler;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_ECHO_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_KEEPALIVE_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.LATEST_SUPPORTED_PROTOCOL_VERSION;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateHandler;

class ConnectionLifetimeHandlerTest {

	/** The lifetime must be long enough that an idle-but-alive pre-configuration socket never trips it, and real. */
	private static final Duration LIFETIME = Duration.ofMillis(200);
	private static final Duration UNAUTHENTICATED = Duration.ofMillis(400);

	@Test
	void idlePreConfigurationSocketIsNotReapedForIdleness() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel(new ConnectionLifetimeHandler(LIFETIME));

		Thread.sleep(LIFETIME.toMillis() / 2);
		channel.runScheduledPendingTasks();

		assertTrue(channel.isOpen());
		channel.finishAndReleaseAll();
	}

	@Test
	void preConfigurationSocketExpiresOnlyAtItsLifetimeTripwire() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel(new ConnectionLifetimeHandler(LIFETIME));

		Thread.sleep(LIFETIME.multipliedBy(3).toMillis());
		channel.runScheduledPendingTasks();

		assertFalse(channel.isOpen());
		channel.releaseInbound();
		channel.releaseOutbound();
	}

	@Test
	void configurationCompletionEndsThePreConfigurationEra() throws Exception {
		ConnectionLifetimeHandler lifetime = new ConnectionLifetimeHandler(LIFETIME);
		EmbeddedChannel channel = new EmbeddedChannel(lifetime, new ConfigurationHandler(lifetime));

		channel.writeInbound(Unpooled.buffer(2).writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION).writeByte(CONFIGURATION_ECHO_TYPE));
		assertNull(channel.pipeline().get(ConfigurationHandler.class));

		Thread.sleep(LIFETIME.multipliedBy(3).toMillis());
		channel.runScheduledPendingTasks();

		assertTrue(channel.isOpen());
		channel.finishAndReleaseAll();
	}

	@Test
	void keepalivesDoNotCountAsConfigurationCompletion() throws Exception {
		ConnectionLifetimeHandler lifetime = new ConnectionLifetimeHandler(LIFETIME);
		EmbeddedChannel channel = new EmbeddedChannel(lifetime, new ConfigurationHandler(lifetime));

		channel.writeInbound(Unpooled.buffer(2).writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION).writeByte(CONFIGURATION_KEEPALIVE_TYPE));
		assertNotNull(channel.pipeline().get(ConfigurationHandler.class));

		Thread.sleep(LIFETIME.multipliedBy(3).toMillis());
		channel.runScheduledPendingTasks();

		// Heartbeats keep the transport warm, but only the configuration echo may end the pre-configuration era.
		assertFalse(channel.isOpen());
		channel.releaseInbound();
		channel.releaseOutbound();
	}

	@Test
	void configuredConnectionExpiresAtTheUnauthenticatedDeadline() throws Exception {
		ConnectionLifetimeHandler lifetime = new ConnectionLifetimeHandler(LIFETIME, UNAUTHENTICATED);
		EmbeddedChannel channel = new EmbeddedChannel(lifetime, new ConfigurationHandler(lifetime));

		channel.writeInbound(Unpooled.buffer(2).writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION).writeByte(CONFIGURATION_ECHO_TYPE));

		// The unauthenticated deadline (400 ms here) outlives the cancelled pre-configuration lifetime (200 ms),
		// so the configuration completing is provably what moved the connection to the longer era.
		Thread.sleep(LIFETIME.multipliedBy(3).dividedBy(2).toMillis());
		channel.runScheduledPendingTasks();
		assertTrue(channel.isOpen());

		// A configured connection that never presents a secret cannot pin the socket past the unauthenticated deadline.
		Thread.sleep(UNAUTHENTICATED.toMillis());
		channel.runScheduledPendingTasks();
		assertFalse(channel.isOpen());
		channel.releaseInbound();
		channel.releaseOutbound();
	}

	@Test
	void authenticationEndsEveryDeadline() throws Exception {
		ConnectionLifetimeHandler lifetime = new ConnectionLifetimeHandler(LIFETIME, UNAUTHENTICATED);
		EmbeddedChannel channel = new EmbeddedChannel(lifetime, new ConfigurationHandler(lifetime));

		channel.writeInbound(Unpooled.buffer(2).writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION).writeByte(CONFIGURATION_ECHO_TYPE));
		lifetime.authenticated(channel.pipeline().context(lifetime));

		Thread.sleep(UNAUTHENTICATED.multipliedBy(3).toMillis());
		channel.runScheduledPendingTasks();

		// An authenticated connection owes no fixed deadline; only the all-idle bound may end it.
		assertTrue(channel.isOpen());
		assertNotNull(channel.pipeline().get(IdleStateHandler.class));
		channel.finishAndReleaseAll();
	}

	@Test
	void theAllIdleDeadlineClosesAnAuthenticatedConnection() throws Exception {
		ConnectionLifetimeHandler lifetime = new ConnectionLifetimeHandler(LIFETIME, UNAUTHENTICATED);
		EmbeddedChannel channel = new EmbeddedChannel(lifetime, new ConfigurationHandler(lifetime));

		channel.writeInbound(Unpooled.buffer(2).writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION).writeByte(CONFIGURATION_ECHO_TYPE));
		lifetime.authenticated(channel.pipeline().context(lifetime), Duration.ofSeconds(1));

		assertTrue(channel.isOpen());
		Thread.sleep(1500);
		channel.runScheduledPendingTasks();

		// A full second of zero reads and zero writes trips Netty's all-idle event, and the handler answers it with a close.
		assertFalse(channel.isOpen());
		channel.releaseInbound();
		channel.releaseOutbound();
	}
}
