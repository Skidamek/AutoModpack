package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlatformUtilsTest {
	@Test
	void classifiesDesktopOperatingSystemsExclusively() {
		// "Darwin" contains "win", so this line also receipts that macOS is classified before Windows.
		assertEquals(PlatformUtils.OperatingSystem.MACOS, PlatformUtils.classify("Darwin"));
		assertEquals(PlatformUtils.OperatingSystem.MACOS, PlatformUtils.classify("Mac OS X"));
		assertEquals(PlatformUtils.OperatingSystem.WINDOWS, PlatformUtils.classify("Windows 11"));
		assertEquals(PlatformUtils.OperatingSystem.LINUX, PlatformUtils.classify("Linux"));
	}

	@Test
	void unrecognizedOperatingSystemsFallToOther() {
		assertEquals(PlatformUtils.OperatingSystem.OTHER, PlatformUtils.classify("FreeBSD"));
		assertEquals(PlatformUtils.OperatingSystem.OTHER, PlatformUtils.classify("SunOS"));
		assertEquals(PlatformUtils.OperatingSystem.OTHER, PlatformUtils.classify(""));
	}
}
