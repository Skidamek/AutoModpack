package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.utils.AddressHelpers;

class BootstrapConfigTest {
	private static final String FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@Test
	void installRequiresAndPreservesConnectionMode() {
		Jsons.KnownHostsBootstrapFields fields = ConfigTools.parse("""
				{
				  "origin": "Play.Example.com",
				  "fingerprint": "01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef",
				  "modpackId": "abc1234",
				  "endpoint": "Downloads.Example.com:25564",
				  "connectionMode": "HOLEPUNCH",
				  "reservedServerListName": "Future value"
				}
				""", Jsons.KnownHostsBootstrapFields.class);

		BootstrapConfig.Validated validated = BootstrapConfig.validate(fields);
		assertEquals("play.example.com:25565", AddressHelpers.formatAddress(validated.origin()));
		assertEquals("downloads.example.com:25564", AddressHelpers.formatAddress(validated.endpoint()));
		assertEquals(FINGERPRINT, validated.fingerprint());
		assertEquals("abc1234", validated.modpackId());
		assertEquals(ModpackConnectionMode.HOLEPUNCH, validated.connectionMode());
	}

	@Test
	void rejectsEndpointWithoutConnectionMode() {
		Jsons.KnownHostsBootstrapFields fields = new Jsons.KnownHostsBootstrapFields();
		fields.origin = "play.example.com";
		fields.fingerprint = FINGERPRINT;
		fields.modpackId = "abc1234";
		fields.endpoint = "downloads.example.com:25564";

		assertThrows(IllegalArgumentException.class, () -> BootstrapConfig.validate(fields));
	}

	@Test
	void rejectsMixedForms() {
		Jsons.KnownHostsBootstrapFields mixed = new Jsons.KnownHostsBootstrapFields();
		mixed.origin = "play.example.com";
		mixed.fingerprint = FINGERPRINT;
		mixed.modpackId = "abc1234";
		assertThrows(IllegalArgumentException.class, () -> BootstrapConfig.validate(mixed));
	}
}
