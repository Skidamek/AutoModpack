package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CertificateTrustEntryTest {
	@Test
	void deserializesTypedTrustEntriesInSharedConnectionState() {
		String fingerprint = "ab".repeat(32);
		Jsons.ConnectionRecordFields trust = ConfigTools.GSON.fromJson("{\"trusts\":{\"play.example.com\":\"" + fingerprint + "\"}}", Jsons.ConnectionRecordFields.class);

		assertEquals(fingerprint, trust.trusts.get("play.example.com").fingerprint);
		assertEquals("TOFU", trust.trusts.get("play.example.com").reason);
		assertTrue(ConfigTools.GSON.toJson(trust).contains("\"reason\": \"TOFU\""));
	}
}
