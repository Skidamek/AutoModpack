package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlatformUtilsTest {
	@Test
	void classifiesDesktopOperatingSystemsExclusively() {
		assertEquals(PlatformUtils.OperatingSystem.MACOS, PlatformUtils.classify("Darwin", "vendor", "vm"));
		assertEquals(PlatformUtils.OperatingSystem.MACOS, PlatformUtils.classify("Mac OS X", "vendor", "vm"));
		assertEquals(PlatformUtils.OperatingSystem.WINDOWS, PlatformUtils.classify("Windows 11", "vendor", "vm"));
		assertEquals(PlatformUtils.OperatingSystem.LINUX, PlatformUtils.classify("Linux", "vendor", "vm"));
	}

	@Test
	void androidRuntimeTakesPrecedenceOverHostName() {
		assertEquals(PlatformUtils.OperatingSystem.ANDROID, PlatformUtils.classify("Linux", "Android", "Dalvik"));
	}
}
