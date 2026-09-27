package pl.skidam.automodpack_core.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.utils.AddressHelpers;

class ServerAddressPinTest {
	private static final String FINGERPRINT = "ab".repeat(32);

	@Test
	void parsesAndFormatsPinnedAddress() {
		String pinned = ServerAddressPin.format("play.example.com:25565", FINGERPRINT.toUpperCase());

		assertEquals("play.example.com:25565#amp1=" + FINGERPRINT, pinned);
		var parsed = ServerAddressPin.parse(pinned);
		assertEquals("play.example.com:25565", parsed.address());
		assertEquals(FINGERPRINT, parsed.fingerprint());
		assertFalse(parsed.isMalformed());
	}

	@Test
	void acceptsBracketedIpv6AndPrettyFingerprint() {
		String pretty = String.join(":", Collections.nCopies(32, "AB"));
		var parsed = ServerAddressPin.parse("[2001:db8::1]:25565#amp1=" + pretty);

		assertEquals("[2001:db8::1]:25565", parsed.address());
		assertEquals(FINGERPRINT, parsed.fingerprint());
		assertEquals("[2001:db8::1]:25565", AddressHelpers.formatAddress(InetSocketAddress.createUnresolved("2001:DB8::1", 25565)));
	}

	@Test
	void leavesPlainAndOtherModAddressesAlone() {
		assertEquals("play.example.com", ServerAddressPin.parse("play.example.com").address());
		assertFalse(ServerAddressPin.parse("play.example.com").hasPin());
		assertEquals("play.example.com#othermod1=value", ServerAddressPin.lenientAddress("play.example.com#othermod1=value"));
	}

	@Test
	void preservesOtherMetadataInEitherOrder() {
		String first = "play.example.com:25565#amp1=" + FINGERPRINT + "#othermod1=value";
		String last = "play.example.com:25565#othermod1=value#amp1=" + FINGERPRINT;

		assertEquals("play.example.com:25565#othermod1=value", ServerAddressPin.lenientAddress(first));
		assertEquals("play.example.com:25565#othermod1=value", ServerAddressPin.lenientAddress(last));
		assertEquals(last, ServerAddressPin.format("play.example.com:25565#othermod1=value", FINGERPRINT));
	}

	@Test
	void rejectsMalformedPinsAndKeepsTheLenientPathFromCarryingThem() {
		assertTrue(ServerAddressPin.parse("#amp1=" + FINGERPRINT).isMalformed());
		assertTrue(ServerAddressPin.parse("play.example.com#amp1=abc").isMalformed());
		assertTrue(ServerAddressPin.parse("play.example.com#amp1=" + FINGERPRINT + "#amp1=" + FINGERPRINT).isMalformed());
		assertTrue(ServerAddressPin.parse("play.example.com#amp1=" + FINGERPRINT + "&other=value").isMalformed());
		// The strict path drops the pin even when its value is broken, which is what saving a server list entry needs.
		assertEquals("play.example.com#other=value", ServerAddressPin.parse("play.example.com#amp1=abc#other=value").address());
		// The lenient path keeps the input, so a broken pin can never reach the address parser the strict path feeds.
		assertEquals("play.example.com#amp1=abc", ServerAddressPin.lenientAddress("play.example.com#amp1=abc"));
	}
}
