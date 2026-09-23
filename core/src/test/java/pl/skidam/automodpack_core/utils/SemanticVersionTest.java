package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SemanticVersionTest {

	@Test
	void ordersTheRealisticModVersionSpace() {
		assertEquals(0, compare("1.0.0+build.5", "1.0.0+build.2"));
		assertEquals(0, compare("v1.2.3", "1.2.3"));
		assertOrder("1.0.0-beta.1", "1.0.0-rc.1", "1.0.0");
		assertOrder("1.0.0-dev", "1.0.0-SNAPSHOT", "1.0.0-alpha.1");
		assertTrue(compare("1.0.0-SNAPSHOT", "1.0.0") < 0);
		assertTrue(compare("1.0.0-fabric", "1.0.0") > 0);
		assertTrue(compare("0.5.3f", "0.5.3") > 0);
		assertTrue(compare("1.16", "1.16.5") < 0);
		assertTrue(compare("1.19.2-v2", "1.19.2") > 0);
		assertTrue(compare("1.2.3-rc1", "1.2.3") < 0);
		assertTrue(compare("1.2.3-beta.1", "1.2.3-beta.2") < 0);
		assertTrue(compare("1.0.0-BETA.1", "1.0.0-beta.2") < 0);
		assertTrue(compare("1.19.2", "1.19.10") < 0);
	}

	@Test
	void parsesTolerantlyButStrictParseKeepsItsRules() {
		assertNull(SemanticVersion.parseOrNull(null));
		assertNull(SemanticVersion.parseOrNull("   "));
		assertNotNull(SemanticVersion.parseOrNull("not-a-version"));
		assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("1.2"));
		assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("abc"));
		assertEquals("1.2.3.rc.9", SemanticVersion.parse("1.2.3-rc.9").toString());
	}

	private static int compare(String left, String right) {
		return SemanticVersion.parseOrNull(left).compareTo(SemanticVersion.parseOrNull(right));
	}

	private static void assertOrder(String lesser, String middle, String greater) {
		assertTrue(compare(lesser, middle) < 0, lesser + " < " + middle);
		assertTrue(compare(middle, greater) < 0, middle + " < " + greater);
	}
}
