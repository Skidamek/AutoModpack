package pl.skidam.automodpack_core.protocol.netty.handler;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_COMPRESSION_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_ECHO_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_KEEPALIVE_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.LATEST_SUPPORTED_PROTOCOL_VERSION;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

class ConfigurationHandlerTest {

	@Test
	void acceptsAndEchoesEachKnownCompressionType() {
		for (CompressionType compressionType : CompressionType.values()) {
			EmbeddedChannel channel = new EmbeddedChannel(new ConfigurationHandler(new PreConfigurationLifetimeHandler()));
			channel.writeInbound(Unpooled.buffer(3).writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION).writeByte(CONFIGURATION_COMPRESSION_TYPE).writeByte(compressionType.wireId()));
			assertEquals(compressionType, NettyServer.compressionCodec(channel).getCompressionType());

			ByteBuf response = channel.readOutbound();
			try {
				assertEquals(LATEST_SUPPORTED_PROTOCOL_VERSION, response.readByte());
				assertEquals(CONFIGURATION_COMPRESSION_TYPE, response.readByte());
				assertEquals(compressionType.wireId(), response.readByte());
			} finally {
				response.release();
				channel.finishAndReleaseAll();
			}
		}
	}

	@Test
	void absorbsKeepalivesSilentlyWithoutCompletingConfiguration() {
		EmbeddedChannel channel = new EmbeddedChannel(new ConfigurationHandler(new PreConfigurationLifetimeHandler()));

		for (int absorbed = 0; absorbed < 3; absorbed++) {
			channel.writeInbound(Unpooled.buffer(2).writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION).writeByte(CONFIGURATION_KEEPALIVE_TYPE));
			assertTrue(channel.isOpen());
			assertNull(channel.readOutbound(), "keepalives must never elicit a reply");
		}
		assertNotNull(channel.pipeline().get(ConfigurationHandler.class), "keepalives must never complete the configuration handshake");

		// The real negotiation still works after any number of absorbed keepalives, interleaved or not.
		channel.writeInbound(Unpooled.buffer(3).writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION).writeByte(CONFIGURATION_COMPRESSION_TYPE).writeByte(CompressionType.ZSTD.wireId()));
		ByteBuf response = channel.readOutbound();
		try {
			assertEquals(LATEST_SUPPORTED_PROTOCOL_VERSION, response.readByte());
			assertEquals(CONFIGURATION_COMPRESSION_TYPE, response.readByte());
		} finally {
			response.release();
		}
		channel.writeInbound(Unpooled.buffer(2).writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION).writeByte(CONFIGURATION_KEEPALIVE_TYPE));
		assertNull(channel.readOutbound());
		channel.writeInbound(Unpooled.buffer(2).writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION).writeByte(CONFIGURATION_ECHO_TYPE));
		assertNull(channel.pipeline().get(ConfigurationHandler.class));
		channel.finishAndReleaseAll();
	}

	@Test
	void rejectsUnknownCompressionType() {
		EmbeddedChannel channel = new EmbeddedChannel(new ConfigurationHandler(new PreConfigurationLifetimeHandler()));
		channel.writeInbound(Unpooled.buffer(3).writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION).writeByte(CONFIGURATION_COMPRESSION_TYPE).writeByte(0x7F));
		assertFalse(channel.isActive());
		channel.finishAndReleaseAll();
	}
}
