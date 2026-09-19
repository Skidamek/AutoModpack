package pl.skidam.automodpack_core.protocol.netty.handler;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAGIC_AMMH;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAGIC_AMOK;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.StagedObject;
import pl.skidam.automodpack_core.modpack.generation.GenerationStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.utils.HashUtils;

class AmmhGateHandlerTest {
	@TempDir
	Path tempDir;

	private ServerConfigJsons.ServerConfigFieldsV3 previousConfig;
	private NettyServer server;

	@BeforeEach
	void setUp() {
		previousConfig = Constants.serverConfig;
		Constants.serverConfig = new ServerConfigJsons.ServerConfigFieldsV3();
		Constants.serverConfig.validateSecrets = false;
		server = new NettyServer();
		server.startSharedTraffic();
		server.startSenders();
	}

	@AfterEach
	void tearDown() {
		server.stop();
		Constants.serverConfig = previousConfig;
	}

	@Test
	void dedicatedMagicMatchSwapsTheChannelOntoTheContractStack() throws Exception {
		fixture();
		EmbeddedChannel channel = new EmbeddedChannel(new AmmhGateHandler(server, Runnable::run, false));

		channel.writeInbound(Unpooled.wrappedBuffer(magicPacket("automodpack.example", request("/head"))));
		channel.runPendingTasks();

		ByteBuf amok = channel.readOutbound();
		try {
			assertEquals(MAGIC_AMOK, amok.readInt());
		} finally {
			amok.release();
		}
		assertTrue(drain(channel).startsWith("HTTP/1.1 200 OK\r\n"));
		assertNull(channel.pipeline().get(AmmhGateHandler.class));
		assertNotNull(channel.pipeline().get(HttpContractHandler.class));
		channel.finishAndReleaseAll();
	}

	@Test
	void dedicatedNonMagicTrafficIsClosed() throws Exception {
		fixture();
		EmbeddedChannel channel = new EmbeddedChannel(new AmmhGateHandler(server, Runnable::run, false));

		channel.writeInbound(Unpooled.wrappedBuffer("GET /head HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8)));

		assertFalse(channel.isActive());
		assertNull(channel.readOutbound());
		channel.finishAndReleaseAll();
	}

	@Test
	void sharedNonMagicTrafficPassesThroughToMinecraftUntouched() {
		EmbeddedChannel channel = new EmbeddedChannel(new AmmhGateHandler(server, Runnable::run, true));
		byte[] minecraftHandshake = {0x10, 0x00, 0x01, 0x02, 0x03};

		assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(minecraftHandshake)));

		ByteBuf forwarded = channel.readInbound();
		try {
			byte[] actual = new byte[forwarded.readableBytes()];
			forwarded.readBytes(actual);
			assertArrayEquals(minecraftHandshake, actual);
		} finally {
			forwarded.release();
		}
		assertNull(channel.pipeline().get(AmmhGateHandler.class));
		assertNull(channel.pipeline().get(HttpContractHandler.class));
		channel.finishAndReleaseAll();
	}

	@Test
	void sharedMagicMatchStripsMinecraftAndServesTheContract() throws Exception {
		fixture();
		EmbeddedChannel channel = new EmbeddedChannel();
		CountingVanillaHandler vanilla = new CountingVanillaHandler();
		channel.pipeline().addLast(new AmmhGateHandler(server, Runnable::run, true));
		channel.pipeline().addLast(vanilla);

		channel.writeInbound(Unpooled.wrappedBuffer(magicPacket("automodpack.example", request("/head"))));
		channel.runPendingTasks();

		assertEquals(0, vanilla.received.get());
		ByteBuf amok = channel.readOutbound();
		try {
			assertEquals(MAGIC_AMOK, amok.readInt());
		} finally {
			amok.release();
		}
		assertTrue(drain(channel).startsWith("HTTP/1.1 200 OK\r\n"));
		assertNull(channel.pipeline().get(AmmhGateHandler.class));
		assertNotNull(channel.pipeline().get(HttpContractHandler.class));
		channel.finishAndReleaseAll();
	}

	private static ByteBuf magicPacket(String hostname, String... tail) {
		byte[] hostnameBytes = hostname.getBytes(StandardCharsets.UTF_8);
		ByteBuf buffer = Unpooled.buffer(Integer.BYTES + Short.BYTES + hostnameBytes.length).writeInt(MAGIC_AMMH).writeShort(hostnameBytes.length)
				.writeBytes(hostnameBytes);
		for (String part : tail) buffer.writeBytes(part.getBytes(StandardCharsets.UTF_8));
		return buffer;
	}

	private static String request(String target) {
		return "GET " + target + " HTTP/1.1\r\n\r\n";
	}

	private static String drain(EmbeddedChannel channel) {
		StringBuilder response = new StringBuilder();
		Object message;
		while ((message = channel.readOutbound()) != null) {
			try {
				response.append(((ByteBuf) message).toString(StandardCharsets.UTF_8));
			} finally {
				ReferenceCountUtil.release(message);
			}
		}
		return response.toString();
	}

	private void fixture() throws Exception {
		GenerationStore store = new GenerationStore(tempDir.resolve("host-generations"), tempDir.resolve("objects"));
		byte[] bytes = "object-payload".getBytes(StandardCharsets.UTF_8);
		Path staging = tempDir.resolve("host-generations").resolve("staging");
		Files.createDirectories(staging);
		Path staged = Files.createTempFile(staging, "candidate-", ".staged");
		Files.write(staged, bytes);
		String hash = HashUtils.getHash(staged);
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		var group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.description = "test";
		group.files = Map.of("config/example.txt", new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(bytes.length), "config", false, hash, null));
		fields.categories = Map.of("General", Map.of("main", group));
		ModpackCandidate candidate = new ModpackCandidate(GroupManifestValidator.validate(fields), new TreeMap<>(Map.of(hash, new StagedObject(hash, bytes.length, staged))), new TreeMap<>(), List.of());
		GenerationStore.Publication publication = store.publish(candidate, "");
		server.replacePaths(publication.hostingPaths());
	}

	/** A stand-in for the vanilla handlers: counts forwarded inbound bytes instead of speaking Minecraft. */
	private static final class CountingVanillaHandler extends ChannelDuplexHandler {
		private final AtomicInteger received = new AtomicInteger();

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			if (msg instanceof ByteBuf buffer) received.addAndGet(buffer.readableBytes());
			ReferenceCountUtil.release(msg);
		}
	}
}
