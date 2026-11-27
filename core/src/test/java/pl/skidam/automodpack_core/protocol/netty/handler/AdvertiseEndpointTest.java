package pl.skidam.automodpack_core.protocol.netty.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import pl.skidam.automodpack_core.client.AdvertiseFetcher;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.config.ServerConfigStore;
import pl.skidam.automodpack_core.config.WireJsons;
import pl.skidam.automodpack_core.modpack.PackAbsence;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;
import pl.skidam.automodpack_core.modpack.host.HostAdvertisement;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

class AdvertiseEndpointTest {
	@TempDir
	Path tempDir;

	@Test
	void advertiseIsPublicAndAdvertiseOnlyRefusesHead() throws Exception {
		ServerConfigJsons.ServerConfigFieldsV3 prev = ServerConfigStore.installed();
		ServerConfigJsons.ServerConfigFieldsV3 cfg = new ServerConfigJsons.ServerConfigFieldsV3();
		cfg.validateSecrets = true;
		cfg.requireModpack = true;
		cfg.modpackHost = true;
		ServerConfigStore.install(cfg);
		try {
			Path headFile = tempDir.resolve("head.json");
			GenerationJsons.HeadDocumentFields head = new GenerationJsons.HeadDocumentFields();
			head.contentToken = "gen-1";
			head.policy = new ModpackJsons.CompleteModpackContentFields();
			head.policy.modpackId = "test-pack";
			head.policy.modpackName = "Test Pack";
			head.policy.mcVersion = "1.21.1";
			head.policy.loader = "fabric";
			head.policy.loaderVersion = "0.16.10";
			Files.writeString(headFile, ConfigTools.GSON.toJson(head), StandardCharsets.UTF_8);

			NettyServer server = new NettyServer();
			server.replacePaths(Map.of(GenerationHosting.HEAD_DOCUMENT_KEY, headFile));

			EmbeddedChannel channel = new EmbeddedChannel(new HttpContractHandler(server, Runnable::run, true));
			channel.writeInbound(Unpooled.copiedBuffer("GET /advertise HTTP/1.1\r\nHost: localhost\r\n\r\n", StandardCharsets.UTF_8));
			String response = readAllOutbound(channel);
			assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response);
			String body = response.substring(response.indexOf("\r\n\r\n") + 4);
			WireJsons.AdvertiseFields parsed = WireJsons.AdvertiseFields.fromJson(body);
			assertEquals(WireJsons.WIRE_CONTRACT_VERSION, parsed.v);
			assertNotNull(parsed.pack);
			assertEquals("test-pack", parsed.pack.id);
			assertEquals("Test Pack", parsed.pack.name);
			assertEquals("1.21.1", parsed.target.mc);

			channel.writeInbound(Unpooled.copiedBuffer("GET /head HTTP/1.1\r\nHost: localhost\r\n\r\n", StandardCharsets.UTF_8));
			String headResp = readAllOutbound(channel);
			assertTrue(headResp.startsWith("HTTP/1.1 404 Not Found\r\n"), headResp);
			channel.finishAndReleaseAll();
		} finally {
			ServerConfigStore.install(prev);
		}
	}

	@Test
	void essentialRelaySuffixDetected() {
		assertTrue(AdvertiseFetcher.isEssentialRelayHost(InetSocketAddress.createUnresolved("1234.essential-sps", 25565)));
		assertTrue(AdvertiseFetcher.isEssentialRelayHost(InetSocketAddress.createUnresolved("ABCD.ESSENTIAL-SPS", 25565)));
		assertFalse(AdvertiseFetcher.isEssentialRelayHost(InetSocketAddress.createUnresolved("play.example.com", 25565)));
	}

	@Test
	void hostAdvertisementClearsRouteOnAbsenceAndCountsDefaultVariantOnly() throws Exception {
		ServerConfigJsons.ServerConfigFieldsV3 prev = ServerConfigStore.installed();
		ServerConfigJsons.ServerConfigFieldsV3 cfg = new ServerConfigJsons.ServerConfigFieldsV3();
		cfg.modpackHost = true;
		cfg.connectionMode = ModpackConnectionMode.HOLEPUNCH;
		ServerConfigStore.install(cfg);
		try {
			WireJsons.AdvertiseFields absent = HostAdvertisement.build(null, null);
			assertNull(absent.pack);
			assertNull(absent.route);
			assertEquals(PackAbsence.Kind.NEVER_PUBLISHED, absent.absence);

			Path headFile = tempDir.resolve("variant-head.json");
			GenerationJsons.HeadDocumentFields head = new GenerationJsons.HeadDocumentFields();
			head.contentToken = "gen-2";
			head.policy = new ModpackJsons.CompleteModpackContentFields();
			head.policy.modpackId = "abc1234";
			head.policy.modpackName = "Variant Pack";
			head.policy.mcVersion = "1.21.1";
			head.policy.loader = "fabric";
			head.policy.loaderVersion = "0.16.10";

			ModpackJsons.CompleteModpackContentFields.ModpackGroupFields core = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
			core.selection = GroupSelectionMode.REQUIRED;
			core.files = Map.of("mods/core.jar", new ModpackJsons.CompleteModpackContentFields.GroupFileFields("100", false, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", null));

			ModpackJsons.CompleteModpackContentFields.ModpackGroupFields opt = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
			opt.selection = GroupSelectionMode.OPTIONAL;
			opt.files = Map.of("mods/optional.jar", new ModpackJsons.CompleteModpackContentFields.GroupFileFields("900", false, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", null));

			Map<String, ModpackJsons.CompleteModpackContentFields.ModpackGroupFields> groups = new LinkedHashMap<>();
			groups.put("core", core);
			groups.put("optional", opt);
			head.policy.groups = groups;
			Files.writeString(headFile, ConfigTools.GSON.toJson(head), StandardCharsets.UTF_8);

			WireJsons.AdvertiseFields ad = HostAdvertisement.build(new GenerationHosting(Map.of(GenerationHosting.HEAD_DOCUMENT_KEY, headFile)), null);
			assertNotNull(ad.pack);
			assertNotNull(ad.route);
			assertEquals(1, ad.pack.files);
			assertEquals(100L, ad.pack.bytes);

			cfg.modpackHost = false;
			WireJsons.AdvertiseFields noHost = HostAdvertisement.build(new GenerationHosting(Map.of(GenerationHosting.HEAD_DOCUMENT_KEY, headFile)), null);
			assertNull(noHost.route);

			// Files-only pack (advertise-versions-to-sync: false) must leave target null rather than synthesizing one.
			head.policy.mcVersion = null;
			head.policy.loader = null;
			head.policy.loaderVersion = null;
			Files.writeString(headFile, ConfigTools.GSON.toJson(head), StandardCharsets.UTF_8);
			WireJsons.AdvertiseFields filesOnly = HostAdvertisement.build(new GenerationHosting(Map.of(GenerationHosting.HEAD_DOCUMENT_KEY, headFile)), null);
			assertNull(filesOnly.target);
		} finally {
			ServerConfigStore.install(prev);
		}
	}

	@Test
	void resolveAdvertisedConnectionHandlesDefaultsAndValidation() {
		InetSocketAddress origin = InetSocketAddress.createUnresolved("play.example.com", 25565);
		assertNull(AdvertiseFetcher.resolveAdvertisedConnection(origin, origin, null, null));

		ConnectionJsons.ConnectionInfo fallback = AdvertiseFetcher.resolveAdvertisedConnection(origin, origin, "fp", new WireJsons.RouteFields(ModpackConnectionMode.HOLEPUNCH, null, null));
		assertEquals("play.example.com", fallback.endpoint.getHostString());
		assertEquals(25565, fallback.endpoint.getPort());
		assertEquals("fp", fallback.expectedFingerprint);

		ConnectionJsons.ConnectionInfo explicit = AdvertiseFetcher.resolveAdvertisedConnection(origin, origin, null, new WireJsons.RouteFields(ModpackConnectionMode.HTTP, "cdn.example.com", 8443));
		assertEquals("cdn.example.com", explicit.endpoint.getHostString());
		assertEquals(8443, explicit.endpoint.getPort());

		assertThrows(IllegalArgumentException.class,
				() -> AdvertiseFetcher.resolveAdvertisedConnection(origin, origin, null, new WireJsons.RouteFields(ModpackConnectionMode.HTTP, "cdn.example.com", 70000)));
	}

	private static String readAllOutbound(EmbeddedChannel channel) {
		StringBuilder sb = new StringBuilder();
		for (;;) {
			ByteBuf buf = channel.readOutbound();
			if (buf == null) break;
			try {
				sb.append(buf.toString(StandardCharsets.UTF_8));
			} finally {
				buf.release();
			}
		}
		return sb.toString();
	}
}
