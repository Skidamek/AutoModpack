package pl.skidam.automodpack_core.protocol.netty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;

class ProtocolPipelineTest {
	@Test
	void transferWorkDoesNotRunOnTheChannelIoExecutor() {
		NettyServer server = new NettyServer();
		EmbeddedChannel channel = new EmbeddedChannel();
		try {
			ProtocolPipeline.install(channel, server, new InetSocketAddress(25565));
			ChannelHandlerContext compression = channel.pipeline().context("compression-encoder");
			ChannelHandlerContext chunkedWrite = channel.pipeline().context("chunked-write");

			assertNotSame(channel.eventLoop(), compression.executor());
			assertSame(compression.executor(), chunkedWrite.executor());
		} finally {
			channel.finishAndReleaseAll();
			server.stop();
		}
	}

	@Test
	void stoppingServerClosesChannelsBeforeTheirTransferExecutor() {
		NettyServer server = new NettyServer();
		EmbeddedChannel channel = new EmbeddedChannel();
		ProtocolPipeline.install(channel, server, new InetSocketAddress(25565));

		server.stop();

		assertFalse(channel.isOpen());
		channel.finishAndReleaseAll();
	}
}
