package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.*;

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
		fields.secret = Secrets.anonymousSecret().secret();
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
}
