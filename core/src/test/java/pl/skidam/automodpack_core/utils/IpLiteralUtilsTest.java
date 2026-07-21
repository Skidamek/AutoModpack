package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

class IpLiteralUtilsTest {
	@Test
	void identifiesOnlyGenuineIpLiterals() {
		assertTrue(IpLiteralUtils.isIpLiteral("203.0.113.8"));
		assertTrue(IpLiteralUtils.isIpLiteral("[2001:db8::8]"));
		assertTrue(IpLiteralUtils.isIpLiteral("2001:db8::8"));
		assertFalse(IpLiteralUtils.isIpLiteral("play.example.com"));
		assertFalse(IpLiteralUtils.isIpLiteral("203.0.113.999"));
		assertFalse(IpLiteralUtils.isIpLiteral("not:an:ip"));
	}

	@Test
	void replacesReverseDnsHostOnlyForLiteralInput() throws Exception {
		byte[] ipv4Loopback = {127, 0, 0, 1};
		InetAddress ptrNamedIpv4 = InetAddress.getByAddress("localhost.example", ipv4Loopback);
		byte[] ipv6Loopback = new byte[16];
		ipv6Loopback[15] = 1;
		InetAddress ptrNamedIpv6 = InetAddress.getByAddress("localhost6.example", ipv6Loopback);

		assertEquals("localhost.example", IpLiteralUtils.preserveLiteralHost("play.example.com", ptrNamedIpv4).getHostName());
		assertEquals("127.0.0.1", IpLiteralUtils.preserveLiteralHost("127.0.0.1", ptrNamedIpv4).getHostName());
		assertEquals("0:0:0:0:0:0:0:1", IpLiteralUtils.preserveLiteralHost("::1", ptrNamedIpv6).getHostName());
	}
}
