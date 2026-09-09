package pl.skidam.automodpack_core.protocol.netty.handler;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_ECHO_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_KEEPALIVE_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.LATEST_SUPPORTED_PROTOCOL_VERSION;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

class PreConfigurationLifetimeHandlerTest {

	/** The lifetime must be long enough that an idle-but-alive pre-configuration socket never trips it, and real. */
	private static final Duration LIFETIME = Duration.ofMillis(200);

	@Test
	void idlePreConfigurationSocketIsNotReapedForIdleness() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel(new PreConfigurationLifetimeHandler(LIFETIME));

		Thread.sleep(LIFETIME.toMillis() / 2);
		channel.runScheduledPendingTasks();

		assertTrue(channel.isOpen());
		channel.finishAndReleaseAll();
	}

	@Test
	void preConfigurationSocketExpiresOnlyAtItsLifetimeTripwire() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel(new PreConfigurationLifetimeHandler(LIFETIME));

		Thread.sleep(LIFETIME.multipliedBy(3).toMillis());
		channel.runScheduledPendingTasks();

		assertFalse(channel.isOpen());
		channel.releaseInbound();
		channel.releaseOutbound();
	}

	@Test
	void configurationCompletionEndsThePreConfigurationEraForGood() throws Exception {
		PreConfigurationLifetimeHandler lifetime = new PreConfigurationLifetimeHandler(LIFETIME);
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
		PreConfigurationLifetimeHandler lifetime = new PreConfigurationLifetimeHandler(LIFETIME);
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
}
