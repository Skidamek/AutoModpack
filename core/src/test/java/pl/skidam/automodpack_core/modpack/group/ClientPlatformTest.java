package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClientPlatformTest {

	@Test
	void parseCanonicalizesCaseAndWhitespace() {
		assertEquals(ClientPlatform.parse("android"), ClientPlatform.parse("Android"));
		assertEquals(ClientPlatform.WINDOWS, ClientPlatform.parse(" Windows "));
		assertEquals(ClientPlatform.LINUX, ClientPlatform.parse("LINUX"));
		assertSame(ClientPlatform.WINDOWS, ClientPlatform.parse("WINDOWS"));
	}

	@Test
	void parsedPlatformsDifferAcrossIds() {
		assertNotEquals(ClientPlatform.parse("android"), ClientPlatform.LINUX);
	}

	@Test
	void parseRejectsBlankAndNull() {
		assertThrows(IllegalArgumentException.class, () -> ClientPlatform.parse("   "));
		assertThrows(IllegalArgumentException.class, () -> ClientPlatform.parse(null));
	}

	@Test
	void identityIsTheCanonicalId() {
		assertEquals("windows", ClientPlatform.WINDOWS.id());
		assertEquals("windows", ClientPlatform.WINDOWS.toString());
		assertEquals(ClientPlatform.parse("HarmonyOS").hashCode(), ClientPlatform.parse("harmonyos").hashCode());
	}
}
