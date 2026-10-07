package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.storage.StoragePaths;

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

	@Test
	void automodpackOwnedInstanceSuffixesFitClassicWindowsBudget() throws ReflectiveOperationException {
		String issuePrefix = "C:\\Users\\USER1\\AppData\\Roaming\\PrismLauncher\\instances\\My Modded Server Season 1\\minecraft";
		String longPrefix = "C:\\Users\\" + "u".repeat(20) + "\\AppData\\Roaming\\PrismLauncher\\instances\\" + "i".repeat(40) + "\\minecraft";
		assertEquals(120, longPrefix.length());
		List<Path> suffixes = automodpackOwnedInstanceSuffixes();
		assertTrue(suffixes.size() > 20, "The declared layout is what is measured, not the list someone remembered");
		for (String prefix : List.of(issuePrefix, longPrefix)) {
			for (Path suffix : suffixes) {
				String nativePath = prefix + "\\" + suffix.toString().replace('/', '\\');
				int published = nativePath.length() + OsPaths.CONFIG_TEMP_OVERHEAD;
				assertTrue(published <= OsPaths.WINDOWS_USABLE_PATH, nativePath + " publishes at " + published);
			}
		}
	}

	/**
	 * Every {@link StoragePaths} constant, so a new path is measured the day it lands, plus the composite names the layout
	 * derives per pack or per file: a hand-kept list only covers the paths someone remembered to add.
	 */
	private static List<Path> automodpackOwnedInstanceSuffixes() throws ReflectiveOperationException {
		List<Path> suffixes = new ArrayList<>();
		for (Field field : StoragePaths.class.getFields())
			if (field.getType() == Path.class) suffixes.add((Path) field.get(null));
		String pack = "abcdefg";
		suffixes.add(StoragePaths.CLIENT_HISTORY_DIR.resolve(pack).resolve("journal.jsonl"));
		suffixes.add(StoragePaths.CLIENT_HISTORY_DIR.resolve(pack).resolve("head.json"));
		suffixes.add(StoragePaths.CLIENT_HISTORY_DIR.resolve(pack).resolve("compaction.json"));
		suffixes.add(StoragePaths.CLIENT_OVERLAYS_DIR.resolve(pack + ".json"));
		suffixes.add(StoragePaths.CLIENT_STATE_HISTORY_DIR.resolve("journal.jsonl"));
		suffixes.add(StoragePaths.CLIENT_STATE_HISTORY_DIR.resolve("trees").resolve("0".repeat(40)));
		suffixes.add(StoragePaths.CLIENT_IMPL_CACHE_DIR.resolve("1.21.11-neoforge.jar"));
		suffixes.add(StoragePaths.SERVER_IMPL_CACHE_DIR.resolve("1.21.11-neoforge.jar"));
		suffixes.add(StoragePaths.CLIENT_DATA_DIR.resolve("known-hosts.json"));
		return suffixes;
	}
}
