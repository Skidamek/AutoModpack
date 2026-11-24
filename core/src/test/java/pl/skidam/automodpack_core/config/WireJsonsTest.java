package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.modpack.PackAbsence;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

class WireJsonsTest {
	@Test
	void connectDataRoundTripsAndDropsSentinels() {
		ServerConfigJsons.ServerConfigFieldsV3 config = new ServerConfigJsons.ServerConfigFieldsV3();
		config.connectionMode = ModpackConnectionMode.HOLEPUNCH;
		config.advertisedEndpointHost = "";
		config.advertisedEndpointPort = -1;

		WireJsons.RouteFields route = WireJsons.RouteFields.fromServerConfig(config);
		assertEquals(ModpackConnectionMode.HOLEPUNCH, route.mode);
		assertNull(route.host);
		assertNull(route.port);

		WireJsons.ConnectDataFields data = new WireJsons.ConnectDataFields("5.0.0-rc.8", "1.21.1", Set.of("fabric"), true, false, route, new Secrets.Secret("tok", 1234L));
		WireJsons.ConnectDataFields parsed = WireJsons.ConnectDataFields.fromJson(data.toJson());
		assertEquals(WireJsons.WIRE_CONTRACT_VERSION, parsed.v);
		assertEquals("5.0.0-rc.8", parsed.amVersion);
		assertEquals("1.21.1", parsed.mcVersion);
		assertEquals(Set.of("fabric"), parsed.accepts);
		assertTrue(parsed.require);
		assertEquals(ModpackConnectionMode.HOLEPUNCH, parsed.route.mode);
		assertNull(parsed.route.host);
		assertNull(parsed.route.port);
		assertEquals("tok", parsed.secret.secret());
	}

	@Test
	void unknownModeOrAbsenceFailsStrictParsing() {
		assertThrows(ConfigTools.ConfigParseException.class, () -> WireJsons.ConnectDataFields.fromJson("{\"v\":1,\"route\":{\"mode\":\"BOGUS\"}}"));
		assertThrows(ConfigTools.ConfigParseException.class, () -> WireJsons.AdvertiseFields.fromJson("{\"v\":1,\"absence\":\"BOGUS\"}"));
	}

	@Test
	void advertiseRoundTrips() {
		WireJsons.AdvertiseFields ad = new WireJsons.AdvertiseFields();
		ad.amVersion = "5.0.0-rc.8";
		ad.pack = new WireJsons.PackSummaryFields("pack-id", "My Pack", 42, 9999L);
		ad.target = new WireJsons.TargetFields("1.21.1", "fabric", "0.16.10");
		ad.require = true;
		ad.route = new WireJsons.RouteFields(ModpackConnectionMode.HOLEPUNCH, null, null);
		ad.generation = "token-1";

		WireJsons.AdvertiseFields parsed = WireJsons.AdvertiseFields.fromJson(ad.toJson());
		assertEquals(WireJsons.WIRE_CONTRACT_VERSION, parsed.v);
		assertEquals("pack-id", parsed.pack.id);
		assertEquals(42, parsed.pack.files);
		assertEquals(9999L, parsed.pack.bytes);
		assertEquals("1.21.1", parsed.target.mc);
		assertNull(parsed.absence);

		WireJsons.AdvertiseFields absent = new WireJsons.AdvertiseFields();
		absent.absence = PackAbsence.Kind.NEVER_PUBLISHED;
		assertEquals(PackAbsence.Kind.NEVER_PUBLISHED, WireJsons.AdvertiseFields.fromJson(absent.toJson()).absence);
	}
}
