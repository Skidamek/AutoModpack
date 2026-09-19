package pl.skidam.automodpack_core.protocol.netty.handler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import pl.skidam.automodpack_core.protocol.netty.NettyServer;

class ProxyProtocolHandlerTest {

	@Test
	void aProxyHeaderFeedsRealRemoteAddrAndPassesTheRestThrough() {
		EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolHandler());
		String proxy = "PROXY TCP4 192.0.2.1 192.0.2.2 5555 25565\r\n";
		byte[] rest = "GET /head HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8);

		channel.writeInbound(Unpooled.wrappedBuffer(proxy.getBytes(StandardCharsets.UTF_8), rest));

		InetSocketAddress remote = (InetSocketAddress) channel.attr(NettyServer.REAL_REMOTE_ADDR).get();
		assertEquals("192.0.2.1", remote.getAddress().getHostAddress());
		assertEquals(5555, remote.getPort());
		assertNull(channel.pipeline().get(ProxyProtocolHandler.class));

		ByteBuf forwarded = channel.readInbound();
		try {
			byte[] actual = new byte[forwarded.readableBytes()];
			forwarded.readBytes(actual);
			assertArrayEquals(rest, actual);
		} finally {
			forwarded.release();
		}
		channel.finishAndReleaseAll();
	}

	@Test
	void nonProxyTrafficPassesUntouched() {
		EmbeddedChannel channel = new EmbeddedChannel(new ProxyProtocolHandler());
		byte[] plain = "PLAIN\r\n".getBytes(StandardCharsets.UTF_8);

		channel.writeInbound(Unpooled.wrappedBuffer(plain));

		assertNull(channel.attr(NettyServer.REAL_REMOTE_ADDR).get());
		assertNull(channel.pipeline().get(ProxyProtocolHandler.class));

		ByteBuf forwarded = channel.readInbound();
		try {
			byte[] actual = new byte[forwarded.readableBytes()];
			forwarded.readBytes(actual);
			assertArrayEquals(plain, actual);
		} finally {
			forwarded.release();
		}
		channel.finishAndReleaseAll();
	}
}
