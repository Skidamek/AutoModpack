package pl.skidam.automodpack_core.protocol.netty.handler;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAGIC_AMMH;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAGIC_AMOK;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.TrafficShaper;

class ProtocolServerHandlerTest {
	private Jsons.ServerConfigFieldsV2 previousConfig;

	@BeforeEach
	void setUp() {
		previousConfig = Constants.serverConfig;
		Constants.serverConfig = new Jsons.ServerConfigFieldsV2();
	}

	@AfterEach
	void tearDown() {
		TrafficShaper.close();
		Constants.serverConfig = previousConfig;
	}

	@Test
	void sharedMagicMismatchReturnsBytesToMinecraftUnchanged() {
		byte[] minecraftHandshake = {0x10, 0x00, 0x01, 0x02, 0x03};
		EmbeddedChannel channel = new EmbeddedChannel(new ProtocolServerHandler(new NettyServer(), ModpackConnectionMode.MAGIC_PACKET, true));

		assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(minecraftHandshake)));
		ByteBuf forwarded = channel.readInbound();
		try {
			byte[] actual = new byte[forwarded.readableBytes()];
			forwarded.readBytes(actual);
			assertArrayEquals(minecraftHandshake, actual);
			assertNull(channel.pipeline().get(ProtocolServerHandler.class));
		} finally {
			forwarded.release();
			channel.finishAndReleaseAll();
		}
	}

	@Test
	void dedicatedMagicRejectsDirectTls() {
		EmbeddedChannel channel = new EmbeddedChannel(new ProtocolServerHandler(new NettyServer(), ModpackConnectionMode.MAGIC_PACKET, false));

		channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x16, 0x03, 0x03, 0x00, 0x00}));

		assertFalse(channel.isActive());
		assertNull(channel.readOutbound());
		channel.finishAndReleaseAll();
	}

	@Test
	void directDoesNotRespondToMagicPacket() {
		new TrafficShaper(null);
		EmbeddedChannel channel = new EmbeddedChannel(new ProtocolServerHandler(new NettyServer(), ModpackConnectionMode.DIRECT, false));

		channel.writeInbound(magicPacket("example.com"));

		Object outbound = channel.readOutbound();
		if (outbound instanceof ByteBuf buffer) {
			try {
				assertFalse(buffer.readableBytes() == Integer.BYTES && buffer.getInt(buffer.readerIndex()) == MAGIC_AMOK);
			} finally {
				buffer.release();
			}
		} else {
			assertNull(outbound);
		}
		assertNull(channel.pipeline().get(ProtocolServerHandler.class));
		channel.finishAndReleaseAll();
	}

	private static ByteBuf magicPacket(String hostname) {
		byte[] hostnameBytes = hostname.getBytes(StandardCharsets.UTF_8);
		return Unpooled.buffer(Integer.BYTES + Short.BYTES + hostnameBytes.length).writeInt(MAGIC_AMMH).writeShort(hostnameBytes.length).writeBytes(hostnameBytes);
	}
}
