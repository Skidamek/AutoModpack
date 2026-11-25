package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.utils.AddressHelpers;

class BootstrapConfigTest {
	private static final String FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@Test
	void installRequiresAndPreservesConnectionMode() {
		String secret = Secrets.generateSecret().secret();
		ConnectionJsons.KnownHostsBootstrapFields fields = ConfigTools.parse("""
				{
				  "origin": "Play.Example.com",
				  "fingerprint": "01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef",
				  "modpackId": "abc1234",
				  "endpoint": "Downloads.Example.com:25564",
				  "connectionMode": "HOLEPUNCH",
				  "secret": "%s",
				  "serverName": "Cool Pack"
				}
				""".formatted(secret), ConnectionJsons.KnownHostsBootstrapFields.class);

		BootstrapConfig.Validated validated = BootstrapConfig.validate(fields);
		assertEquals("play.example.com:25565", AddressHelpers.formatAddress(validated.origin()));
		assertEquals("downloads.example.com:25564", AddressHelpers.formatAddress(validated.endpoint()));
		assertEquals(FINGERPRINT, validated.fingerprint());
		assertEquals("abc1234", validated.modpackId());
		assertEquals(ModpackConnectionMode.HOLEPUNCH, validated.connectionMode());
		assertEquals(secret, validated.secret());
		assertEquals("Cool Pack", validated.serverName());
	}

	@Test
	void installWithoutSecretOrNameSkipsServerList() {
		ConnectionJsons.KnownHostsBootstrapFields fields = new ConnectionJsons.KnownHostsBootstrapFields();
		fields.origin = "play.example.com";
		fields.fingerprint = FINGERPRINT;
		fields.modpackId = "abc1234";
		fields.endpoint = "downloads.example.com:25564";
		fields.connectionMode = ModpackConnectionMode.HOLEPUNCH;

		BootstrapConfig.Validated validated = BootstrapConfig.validate(fields);
		assertNull(validated.secret());
		assertNull(validated.serverName());
		assertFalse(validated.hasSecret());
		assertFalse(validated.hasServerName());
	}

	@Test
	void httpBootstrapValidatesAndInstallsWithoutSecret() {
		ConnectionJsons.KnownHostsBootstrapFields fields = ConfigTools.parse("""
				{
				  "origin": "Play.Example.com",
				  "modpackId": "abc1234",
				  "endpoint": "Downloads.Example.com:25564",
				  "connectionMode": "HTTP"
				}
				""", ConnectionJsons.KnownHostsBootstrapFields.class);

		BootstrapConfig.Validated validated = BootstrapConfig.validate(fields);
		assertEquals("play.example.com:25565", AddressHelpers.formatAddress(validated.origin()));
		assertEquals("downloads.example.com:25564", AddressHelpers.formatAddress(validated.endpoint()));
		assertEquals("abc1234", validated.modpackId());
		assertEquals(ModpackConnectionMode.HTTP, validated.connectionMode());
		assertTrue(validated.installsModpack());
		assertFalse(validated.hasSecret());
		assertNull(validated.fingerprint());

		ConnectionJsons.KnownHostsBootstrapFields installed = BootstrapConfig.install(validated.origin(), null, validated.modpackId(), validated.endpoint(), ModpackConnectionMode.HTTP, null);
		assertEquals(ModpackConnectionMode.HTTP, installed.connectionMode);
		assertNull(installed.secret);
	}

	@Test
	void originAloneIsValidWithoutFingerprint() {
		ConnectionJsons.KnownHostsBootstrapFields fields = new ConnectionJsons.KnownHostsBootstrapFields();
		fields.origin = "play.example.com";
		BootstrapConfig.Validated validated = BootstrapConfig.validate(fields);
		assertNull(validated.fingerprint());
		assertFalse(validated.installsModpack());
		assertNull(validated.serverName());
	}

	@Test
	void pinRejectsSecret() {
		ConnectionJsons.KnownHostsBootstrapFields fields = new ConnectionJsons.KnownHostsBootstrapFields();
		fields.origin = "play.example.com";
		fields.fingerprint = FINGERPRINT;
		fields.secret = Secrets.generateSecret().secret();
		assertThrows(IllegalArgumentException.class, () -> BootstrapConfig.validate(fields));
	}

	@Test
	void rejectsAnonymousSecret() {
		ConnectionJsons.KnownHostsBootstrapFields fields = new ConnectionJsons.KnownHostsBootstrapFields();
		fields.origin = "play.example.com";
		fields.fingerprint = FINGERPRINT;
		fields.modpackId = "abc1234";
		fields.endpoint = "downloads.example.com:25564";
		fields.connectionMode = ModpackConnectionMode.HOLEPUNCH;
		fields.secret = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[Secrets.BYTE_LENGTH]);
		assertThrows(IllegalArgumentException.class, () -> BootstrapConfig.validate(fields));
	}

	@Test
	void rejectsEndpointWithoutConnectionMode() {
		ConnectionJsons.KnownHostsBootstrapFields fields = new ConnectionJsons.KnownHostsBootstrapFields();
		fields.origin = "play.example.com";
		fields.fingerprint = FINGERPRINT;
		fields.modpackId = "abc1234";
		fields.endpoint = "downloads.example.com:25564";

		assertThrows(IllegalArgumentException.class, () -> BootstrapConfig.validate(fields));
	}

	@Test
	void rejectsMixedForms() {
		ConnectionJsons.KnownHostsBootstrapFields mixed = new ConnectionJsons.KnownHostsBootstrapFields();
		mixed.origin = "play.example.com";
		mixed.fingerprint = FINGERPRINT;
		mixed.modpackId = "abc1234";
		assertThrows(IllegalArgumentException.class, () -> BootstrapConfig.validate(mixed));
	}

	@Test
	void resolveEndpointUsesTheMinecraftPortForHolepunch() {
		assertEquals("play.example.com:25566", resolve("play.example.com:25566", new ServerConfigJsons.ServerConfigFieldsV3(), ModpackConnectionMode.HOLEPUNCH));
	}

	@Test
	void resolveEndpointUsesBindPortForMagicOnlyWhenSet() {
		ServerConfigJsons.ServerConfigFieldsV3 bound = new ServerConfigJsons.ServerConfigFieldsV3();
		bound.bindPort = 25567;
		assertEquals("play.example.com:25567", resolve("play.example.com:25566", bound, ModpackConnectionMode.MAGIC));
		assertEquals("play.example.com:25566", resolve("play.example.com:25566", new ServerConfigJsons.ServerConfigFieldsV3(), ModpackConnectionMode.MAGIC));
	}

	@Test
	void resolveEndpointRequiresBindPortForHttp() {
		ServerConfigJsons.ServerConfigFieldsV3 bound = new ServerConfigJsons.ServerConfigFieldsV3();
		bound.bindPort = 25568;
		assertEquals("play.example.com:25568", resolve("play.example.com:25566", bound, ModpackConnectionMode.HTTP));
		assertThrows(IllegalArgumentException.class, () -> resolve("play.example.com:25566", new ServerConfigJsons.ServerConfigFieldsV3(), ModpackConnectionMode.HTTP));
	}

	@Test
	void resolveEndpointPrefersAdvertisedEndpointOverTheMode() {
		ServerConfigJsons.ServerConfigFieldsV3 advertised = new ServerConfigJsons.ServerConfigFieldsV3();
		advertised.advertisedEndpointHost = " Downloads.Example.com ";
		advertised.advertisedEndpointPort = 25564;
		advertised.bindPort = 25568;
		assertEquals("downloads.example.com:25564", resolve("play.example.com:25566", advertised, ModpackConnectionMode.HTTP));
		assertEquals("downloads.example.com:25564", resolve("play.example.com:25566", advertised, ModpackConnectionMode.HOLEPUNCH));
	}

	@Test
	void resolveEndpointKeepsAnIpv6OriginBracketed() {
		assertEquals("[::1]:25566", resolve("[::1]:25566", new ServerConfigJsons.ServerConfigFieldsV3(), ModpackConnectionMode.HOLEPUNCH));

		ServerConfigJsons.ServerConfigFieldsV3 advertised = new ServerConfigJsons.ServerConfigFieldsV3();
		advertised.advertisedEndpointHost = "2001:DB8::1";
		advertised.advertisedEndpointPort = 25564;
		assertEquals("[2001:db8::1]:25564", resolve("[::1]:25566", advertised, ModpackConnectionMode.HOLEPUNCH));
	}

	@Test
	void resolveEndpointUsesAnExplicitEndpointAsGiven() {
		ServerConfigJsons.ServerConfigFieldsV3 unconfigured = new ServerConfigJsons.ServerConfigFieldsV3();
		assertEquals("downloads.example.com:25564",
				AddressHelpers.formatAddress(BootstrapConfig.resolveEndpoint(AddressHelpers.parseOrigin("play.example.com"), unconfigured, "Downloads.Example.com:25564", ModpackConnectionMode.HTTP)));
	}

	private static String resolve(String origin, ServerConfigJsons.ServerConfigFieldsV3 config, ModpackConnectionMode mode) {
		return AddressHelpers.formatAddress(BootstrapConfig.resolveEndpoint(AddressHelpers.parseOrigin(origin), config, null, mode));
	}
}
