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
	void windowsBudgetIsMaxPathMinusNulAndDoublesWithTheLongPathOptIn() {
		assertEquals(260, OsPaths.WINDOWS_MAX_PATH);
		assertEquals(259, OsPaths.WINDOWS_USABLE_PATH);
		assertEquals(32766, OsPaths.WINDOWS_LONG_USABLE_PATH);
		assertEquals(25, OsPaths.VERIFIED_TEMP_OVERHEAD);
		assertEquals(42, OsPaths.CONFIG_TEMP_OVERHEAD);
		assertEquals(259, OsPaths.maxPath(PlatformUtils.OperatingSystem.WINDOWS, false));
		assertEquals(32766, OsPaths.maxPath(PlatformUtils.OperatingSystem.WINDOWS, true));
	}

	@Test
	void windowsRejectsAPathThatWouldOverflowAfterPublicationTemp() {
		Path path = tempDir.resolve("file.txt");
		int nativeLength = OsPaths.nativeString(path, PlatformUtils.OperatingSystem.WINDOWS).length();
		int overflow = OsPaths.WINDOWS_USABLE_PATH - nativeLength + 1;
		IOException thrown = assertThrows(IOException.class, () -> OsPaths.requirePublishable(path, PlatformUtils.OperatingSystem.WINDOWS, overflow, false));
		assertTrue(thrown.getMessage().contains("publishable limit"));
		assertTrue(thrown.getMessage().contains("long paths"));
	}

	@Test
	void windowsAcceptsAnOverflowingPathOnceLongPathsAreEnabled() {
		Path path = tempDir.resolve("file.txt");
		int nativeLength = OsPaths.nativeString(path, PlatformUtils.OperatingSystem.WINDOWS).length();
		int justPastClassicBudget = OsPaths.WINDOWS_USABLE_PATH - nativeLength + 1;
		assertThrows(IOException.class, () -> OsPaths.requirePublishable(path, PlatformUtils.OperatingSystem.WINDOWS, justPastClassicBudget, false));
		assertDoesNotThrow(() -> OsPaths.requirePublishable(path, PlatformUtils.OperatingSystem.WINDOWS, justPastClassicBudget, true));
	}

	@Test
	void windowsAcceptsAPathThatFitsWithPublicationTemp() throws IOException {
		Path path = tempDir.resolve("file.txt");
		int nativeLength = OsPaths.nativeString(path, PlatformUtils.OperatingSystem.WINDOWS).length();
		int overhead = OsPaths.WINDOWS_USABLE_PATH - nativeLength;
		OsPaths.requirePublishable(path, PlatformUtils.OperatingSystem.WINDOWS, overhead, false);
	}

	/**
	 * The component rule is exercised against Linux's 4095 total budget so the ambient temp prefix cannot decide
	 * which limit fires; on Windows runners the classic budget trips first for long prefixes.
	 */
	@Test
	void rejectsAFileNameComponentPast255MinusTempOverhead() {
		Path path = tempDir.resolve("a".repeat(OsPaths.MAX_COMPONENT - OsPaths.VERIFIED_TEMP_OVERHEAD + 1));
		IOException thrown = assertThrows(IOException.class, () -> OsPaths.requirePublishable(path, PlatformUtils.OperatingSystem.LINUX, OsPaths.VERIFIED_TEMP_OVERHEAD, false));
		assertTrue(thrown.getMessage().contains("component"));
	}

	@Test
	void currentOsAcceptsAShortManagedPath() {
		assertDoesNotThrow(() -> OsPaths.requirePublishableFile(tempDir.resolve("mods/example.jar")));
		assertDoesNotThrow(() -> OsPaths.requirePublishableDirectory(tempDir.resolve("automodpack/client")));
	}
}
