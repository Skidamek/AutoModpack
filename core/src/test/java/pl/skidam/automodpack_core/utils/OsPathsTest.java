package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OsPathsTest {
	@TempDir
	Path tempDir;

	@Test
	void windowsBudgetIsMaxPathMinusNulMinusVerifiedTempOverhead() {
		assertEquals(260, OsPaths.WINDOWS_MAX_PATH);
		assertEquals(259, OsPaths.WINDOWS_USABLE_PATH);
		assertEquals(25, OsPaths.VERIFIED_TEMP_OVERHEAD);
		assertEquals(42, OsPaths.CONFIG_TEMP_OVERHEAD);
		assertEquals(259, OsPaths.maxPath(PlatformUtils.OperatingSystem.WINDOWS));
	}

	@Test
	void windowsRejectsAPathThatWouldOverflowAfterPublicationTemp() {
		Path path = tempDir.resolve("file.txt");
		int nativeLength = OsPaths.nativeString(path, PlatformUtils.OperatingSystem.WINDOWS).length();
		int overflow = OsPaths.WINDOWS_USABLE_PATH - nativeLength + 1;
		IOException thrown = assertThrows(IOException.class, () -> OsPaths.requirePublishable(path, PlatformUtils.OperatingSystem.WINDOWS, overflow));
		assertTrue(thrown.getMessage().contains("publishable limit"));
	}

	@Test
	void windowsAcceptsAPathThatFitsWithPublicationTemp() throws IOException {
		Path path = tempDir.resolve("file.txt");
		int nativeLength = OsPaths.nativeString(path, PlatformUtils.OperatingSystem.WINDOWS).length();
		int overhead = OsPaths.WINDOWS_USABLE_PATH - nativeLength;
		OsPaths.requirePublishable(path, PlatformUtils.OperatingSystem.WINDOWS, overhead);
	}

	@Test
	void rejectsAFileNameComponentPast255MinusTempOverhead() {
		Path path = tempDir.resolve("a".repeat(OsPaths.MAX_COMPONENT - OsPaths.VERIFIED_TEMP_OVERHEAD + 1));
		IOException thrown = assertThrows(IOException.class, () -> OsPaths.requirePublishable(path, PlatformUtils.operatingSystem(), OsPaths.VERIFIED_TEMP_OVERHEAD));
		assertTrue(thrown.getMessage().contains("component"));
	}

	@Test
	void currentOsAcceptsAShortManagedPath() {
		assertDoesNotThrow(() -> OsPaths.requirePublishableFile(tempDir.resolve("mods/example.jar")));
		assertDoesNotThrow(() -> OsPaths.requirePublishableDirectory(tempDir.resolve("automodpack/client")));
	}
}
