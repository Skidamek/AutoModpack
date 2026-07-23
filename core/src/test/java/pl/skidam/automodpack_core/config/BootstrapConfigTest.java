package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.utils.AddressHelpers;

class BootstrapConfigTest {
	private static final String FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@Test
	void installDefaultsMagicAndToleratesReservedFields() {
		Jsons.KnownHostsBootstrapFields fields = ConfigTools.parse("""
				{
				  "origin": "Play.Example.com",
				  "fingerprint": "01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef",
				  "modpackId": "abc1234",
				  "endpoint": "Downloads.Example.com:25564",
				  "reservedServerListName": "Future value"
				}
				""", Jsons.KnownHostsBootstrapFields.class);

		BootstrapConfig.Validated validated = BootstrapConfig.validate(fields);
		assertEquals("play.example.com:25565", AddressHelpers.formatAddress(validated.origin()));
		assertEquals("downloads.example.com:25564", AddressHelpers.formatAddress(validated.endpoint()));
		assertEquals(FINGERPRINT, validated.fingerprint());
		assertEquals("abc1234", validated.modpackId());
		assertTrue(validated.requiresMagic());
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
