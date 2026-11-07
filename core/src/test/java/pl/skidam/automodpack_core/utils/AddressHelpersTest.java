package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;

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
	void acceptsNumericMinecraftHosts() {
		var origin = AddressHelpers.format("127.0.0", 25565);
		var shortOrigin = AddressHelpers.format("0.0", 25565);

		assertEquals("127.0.0", origin.getHostString());
		assertEquals(25565, origin.getPort());
		assertEquals("0.0", shortOrigin.getHostString());
		assertEquals(25565, shortOrigin.getPort());
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

	@Test
	void endpointHostKeepsTheReachedLiteralForLiteralAndLoopbackJoins() throws Exception {
		InetAddress ptrLoopback4 = InetAddress.getByAddress("localhost.example", new byte[]{127, 0, 0, 1});
		InetSocketAddress connected4 = new InetSocketAddress(ptrLoopback4, 25570);

		assertEquals("127.0.0.1", AddressHelpers.endpointHost(connected4, AddressHelpers.format("127.0.0.1", 25570)));
		assertEquals("127.0.0.1", AddressHelpers.endpointHost(connected4, AddressHelpers.format("localhost", 25570)));

		byte[] loopback6Bytes = new byte[16];
		loopback6Bytes[15] = 1;
		InetAddress ptrLoopback6 = InetAddress.getByAddress("localhost6.example", loopback6Bytes);
		InetSocketAddress connected6 = new InetSocketAddress(ptrLoopback6, 25570);

		String literal6 = AddressHelpers.endpointHost(connected6, AddressHelpers.format("::1", 25570));
		assertEquals("::1", AddressHelpers.format(literal6, 25570).getHostString());
	}

	@Test
	void endpointHostKeepsTheConnectedHostnameForHostnameAndCustomTransports() throws Exception {
		InetAddress edge = InetAddress.getByAddress("edge.example.com", new byte[]{(byte) 203, 0, 113, 8});
		assertEquals("edge.example.com", AddressHelpers.endpointHost(new InetSocketAddress(edge, 25565), AddressHelpers.format("play.example.com", 25565)));

		InetSocketAddress customTransport = AddressHelpers.format("random.e4mc.link", 25565);
		assertEquals("random.e4mc.link", AddressHelpers.endpointHost(customTransport, customTransport));
	}

	@Test
	void identifiesLoopbackOnlyForLiteralAddresses() {
		assertTrue(AddressHelpers.isLoopbackLiteral("127.0.0.1"));
		assertTrue(AddressHelpers.isLoopbackLiteral("::1"));
		assertFalse(AddressHelpers.isLoopbackLiteral("203.0.113.8"));
		assertFalse(AddressHelpers.isLoopbackLiteral("localhost"));
	}

	@Test
	void formatsResolvedAddressesFromTheirBytes() throws Exception {
		InetAddress ptrNamed = InetAddress.getByAddress("localhost.example", new byte[]{127, 0, 0, 1});
		assertEquals("127.0.0.1:25565", AddressHelpers.formatAddressLiteral(new InetSocketAddress(ptrNamed, 25565)));

		byte[] loopback6 = new byte[16];
		loopback6[15] = 1;
		InetAddress ptrNamed6 = InetAddress.getByAddress("localhost6.example", loopback6);
		assertEquals("[0:0:0:0:0:0:0:1]:25565", AddressHelpers.formatAddressLiteral(new InetSocketAddress(ptrNamed6, 25565)));
	}
}
