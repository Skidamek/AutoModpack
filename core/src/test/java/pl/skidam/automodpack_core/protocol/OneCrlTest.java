package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class OneCrlTest {

	// Atos TrustedRoot Server-CA 2011, revoked in OneCRL by bug 1312150: https://crt.sh/?id=705899
	private static final String ATOS_SERVER_CA_PEM = """
			-----BEGIN CERTIFICATE-----
			MIIDgTCCAmmgAwIBAgIIa12RvBNhznUwDQYJKoZIhvcNAQELBQAwPDEeMBwGA1UE
			AwwVQXRvcyBUcnVzdGVkUm9vdCAyMDExMQ0wCwYDVQQKDARBdG9zMQswCQYDVQQG
			EwJERTAeFw0xMTA3MDcxNTA1MjlaFw0yMTA3MDQxNTA1MjlaMEYxKDAmBgNVBAMM
			H0F0b3MgVHJ1c3RlZFJvb3QgU2VydmVyLUNBIDIwMTExDTALBgNVBAoMBEF0b3Mx
			CzAJBgNVBAYTAkRFMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwY6T
			vlvG81rQGF16obKVhvVQF9isSeQ9/hwlWWd4vtyMr19AJIVNtq+Sizh/raKiecL4
			xubWrOiH/zUskHbMdFNUH7uvFf+tiuXnArxvSX9Ozw0P+Eolmh3+vRb5e9WZ4ODt
			wvY8GbBNLiC7MIhl9rfsvPQykiTtPh0kUfoxzDxHgt00hIwX35eOdupLKTFdz/Bf
			vIzKRN+lcbx8BerCX34zmE+pp+FSBqxazm/z1fWKmg73Oqug/fJHoNXXodbQrqwB
			mQSDDCCeFhJoqNQEmlFqOD/sxFF/HgMlvXbYAU6kdVWTYw3zaZLS/G8h8a66Hpr4
			x+0bH/ubZQPhggOY6QIDAQABo30wezAdBgNVHQ4EFgQU6vjeQYJbQ3CLSPOih/5W
			8BOY9d0wDwYDVR0TAQH/BAUwAwEB/zAfBgNVHSMEGDAWgBSnpQaxLKYJYO7Rl+lw
			rrw7GWzbITAYBgNVHSAEETAPMA0GCysGAQQBsC0DBAEBMA4GA1UdDwEB/wQEAwIB
			hjANBgkqhkiG9w0BAQsFAAOCAQEALjuTXGBbCYqau1LuwrHlNPoQKYNFJ2FFCVf1
			KyQt7xcd02po0JX7yqRqgNoI3CIGPtbJ1PCofGuzlt1gkHS2fWSdAOlyX3sBQrxA
			ygcRCijB8MDFt9azFvQke4BwiLrT6wD7Sq1bxJK9f8l6H91ociHynxOUJ6CSiKK+
			MYCUGPfKrfLeuFB8bUU+TdDiMR3+OzuPm2uLsLKplZB+3ey/luc9hCKurVkKMrEV
			uo39UmvWiV1i/iW6akrDCvqm9L3C4jPwJkQuOsy/KHbeEciNV+9uUDr4GFX0SxUP
			iZAEEzOFwcWJ3Db5VS1gQ2a0ciO/7UZsfJqwjDwdlCfSqYP8fg==
			-----END CERTIFICATE-----
			""";
	// That certificate's record exactly as Mozilla publishes it, id 33154d98-0f20-7e7d-d2d0-3244a7d1f971.
	private static final String ATOS_RECORD = "{\"enabled\":true,\"issuerName\":\"MDwxHjAcBgNVBAMMFUF0b3MgVHJ1c3RlZFJvb3QgMjAxMTENMAsGA1UECgwEQXRvczELMAkGA1UEBhMCREU=\",\"serialNumber\":\"a12RvBNhznU=\"}";

	@Test
	void parseMozillaKeepsEnabledIssuerSerialAndSpkiAndDropsDisabled() {
		byte[] body = """
				{"data":[
				  {"enabled":true,"issuerName":"QQ==","serialNumber":"Qg=="},
				  {"enabled":false,"issuerName":"QQ==","serialNumber":"Qw=="},
				  {"enabled":true,"pubKeyHash":"QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE="}
				]}
				""".getBytes(StandardCharsets.UTF_8);
		OneCrl.Blocklist list = OneCrl.parseMozilla(body);
		assertEquals(1, list.issuerSerial().size());
		assertEquals(1, list.spkiHex().size());
		assertTrue(list.issuerSerial().contains(OneCrl.issuerSerialKey(b64("QQ=="), b64("Qg=="))));
		assertFalse(list.issuerSerial().contains(OneCrl.issuerSerialKey(b64("QQ=="), b64("Qw=="))));
	}

	@Test
	void aMozillaIssuerSerialRecordMatchesTheRealCertificateItNames() throws Exception {
		X509Certificate certificate;
		try (var in = new ByteArrayInputStream(ATOS_SERVER_CA_PEM.getBytes(StandardCharsets.US_ASCII))) {
			certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
		}
		OneCrl.Blocklist list = OneCrl.parseMozilla(("{\"data\":[" + ATOS_RECORD + "]}").getBytes(StandardCharsets.UTF_8));
		assertTrue(list.blocks(certificate), "the issuer DER and serial encodings must match Mozilla's bytes");
		assertFalse(list.blocks(NetUtils.selfSign(NetUtils.generateKeyPair())));
	}

	@Test
	void anEmptyFeedOrOneAtThePageLimitIsUnusable() {
		String record = "{\"enabled\":true,\"issuerName\":\"QQ==\",\"serialNumber\":\"Qg==\"}";
		assertNull(OneCrl.parseMozilla(("{\"data\":[" + (record + ",").repeat(9_999) + record + "]}").getBytes(StandardCharsets.UTF_8)));
		assertNull(OneCrl.parseMozilla("{\"data\":[]}".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void theBundledSnapshotIsAWholeMozillaList() {
		OneCrl.Blocklist snapshot = OneCrl.loadBundledSnapshot();
		assertTrue(snapshot.issuerSerial().size() > 1000, "refresh core/src/main/onecrl/onecrl.json with core/src/main/onecrl/update.py");
		assertFalse(snapshot.spkiHex().isEmpty());
	}

	private static byte[] b64(String value) {
		int pad = (4 - value.length() % 4) % 4;
		return Base64.getDecoder().decode(pad == 0 ? value : value + "=".repeat(pad));
	}
}
