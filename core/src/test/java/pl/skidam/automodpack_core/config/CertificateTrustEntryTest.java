package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CertificateTrustEntryTest {
	@Test
	void migratesProductionKnownHostFingerprintToTypedEntry() {
		String fingerprint = "ab".repeat(32);
		Jsons.KnownHostsFields trust = ConfigTools.GSON.fromJson("{\"hosts\":{\"play.example.com\":\"" + fingerprint + "\"}}", Jsons.KnownHostsFields.class);

		assertEquals(fingerprint, trust.hosts.get("play.example.com").fingerprint);
		assertEquals("TOFU", trust.hosts.get("play.example.com").reason);
		assertTrue(ConfigTools.GSON.toJson(trust).contains("\"reason\": \"TOFU\""));
	}
}
