package pl.skidam.automodpack_core.protocol.netty.handler;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_COMPRESSION_TYPE;
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
			EmbeddedChannel channel = new EmbeddedChannel(new ConfigurationHandler());
			channel.writeInbound(Unpooled.buffer(3).writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION).writeByte(CONFIGURATION_COMPRESSION_TYPE).writeByte(compressionType.wireId()));
			assertEquals(compressionType, channel.attr(NettyServer.COMPRESSION_TYPE).get());

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
	void rejectsUnknownCompressionType() {
		EmbeddedChannel channel = new EmbeddedChannel(new ConfigurationHandler());
		channel.writeInbound(Unpooled.buffer(3).writeByte(LATEST_SUPPORTED_PROTOCOL_VERSION).writeByte(CONFIGURATION_COMPRESSION_TYPE).writeByte(0x7F));
		assertFalse(channel.isActive());
		channel.finishAndReleaseAll();
	}
}
