package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AddressHelpersTest {
	@Test
	void normalizesDnsAndDefaultOriginPortWithoutResolution() {
		var origin = AddressHelpers.parseOrigin("  BÜCHER.Example.  ");

		assertTrue(origin.isUnresolved());
		assertEquals("xn--bcher-kva.example", origin.getHostString());
		assertEquals(25565, origin.getPort());
		assertEquals("xn--bcher-kva.example:25565", AddressHelpers.formatAddress(origin));
	}

	@Test
	void normalizesIpv4AndPreservesExplicitOriginPort() {
		var origin = AddressHelpers.parseOrigin("192.0.2.10:24444");

		assertTrue(origin.isUnresolved());
		assertEquals("192.0.2.10", origin.getHostString());
		assertEquals(24444, origin.getPort());
	}

	@Test
	void canonicalizesAndBracketsIpv6() {
		var defaultPort = AddressHelpers.parseOrigin("2001:0DB8:0:0:0:0:0:1");
		var explicitPort = AddressHelpers.parseEndpoint("[2001:0DB8:0:0:0:0:0:1]:24444");

		assertEquals("[2001:db8::1]:25565", AddressHelpers.formatAddress(defaultPort));
		assertEquals("[2001:db8::1]:24444", AddressHelpers.formatAddress(explicitPort));
		assertTrue(defaultPort.isUnresolved());
		assertTrue(explicitPort.isUnresolved());
	}

	@Test
	void endpointRequiresValidExplicitPort() {
		assertThrows(IllegalArgumentException.class, () -> AddressHelpers.parseEndpoint("downloads.example.com"));
		assertThrows(IllegalArgumentException.class, () -> AddressHelpers.parseEndpoint("downloads.example.com:0"));
		assertThrows(IllegalArgumentException.class, () -> AddressHelpers.parseEndpoint("downloads.example.com:65536"));
		assertThrows(IllegalArgumentException.class, () -> AddressHelpers.parseEndpoint("2001:db8::1"));

		var endpoint = AddressHelpers.parseEndpoint("Downloads.Example.com:24444");
		assertEquals("downloads.example.com:24444", AddressHelpers.formatAddress(endpoint));
	}
}
