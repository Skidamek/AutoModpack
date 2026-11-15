package pl.skidam.automodpack_core.utils.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.PlatformUtils;

/** The reparse-tag classifier and, on Windows, the tags {@code compact.exe} and cloud placeholders actually write. */
class WindowsFileStatTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void benignReparseTagsAreCompressionOrPlaceholderMetadata() {
		assertTrue(WindowsFileStat.isBenignReparseTag(0x80000013L), "dedup");
		assertTrue(WindowsFileStat.isBenignReparseTag(0x80000017L), "WOF compression written by compact.exe");
		assertTrue(WindowsFileStat.isBenignReparseTag(0x9000001AL), "cloud");
		assertTrue(WindowsFileStat.isBenignReparseTag(0x9000101AL), "cloud variant 1");
		assertTrue(WindowsFileStat.isBenignReparseTag(0x9000F01AL), "cloud variant F");
		assertFalse(WindowsFileStat.isBenignReparseTag(0xA000000CL), "symbolic link");
		assertFalse(WindowsFileStat.isBenignReparseTag(0xA0000003L), "junction (mount point)");
		assertFalse(WindowsFileStat.isBenignReparseTag(0x80000000L), "an unknown reparse tag stays a redirect");
	}

	@Test
	void compactedFileIsRegularAndNotAJunction() throws Exception {
		Assumptions.assumeTrue(PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS, "compact.exe is a Windows tool");
		Path file = temporaryDirectory.resolve("compacted.bin");
		Process create = new ProcessBuilder("fsutil", "file", "createnew", file.toString(), "2097152").redirectErrorStream(true).start();
		assertEqualsZero(create.waitFor(), "fsutil file createnew");
		Process compact = new ProcessBuilder("compact", "/c", "/f", file.toString()).redirectErrorStream(true).start();
		byte[] output = compact.getInputStream().readAllBytes();
		Assumptions.assumeTrue(compact.waitFor() == 0, "compact.exe could not compress this file: " + new String(output));

		FileCache.StatSnapshot snapshot = WindowsFileStat.statSnapshot(file);
		Assumptions.assumeTrue(snapshot != null, "the Windows native stat is unavailable: " + WindowsFileStat.loadError());
		assertTrue(snapshot.regularFile(), "a WOF-compressed file is still a regular file");
		assertFalse(snapshot.junction(), "WOF compression is not a junction");
		assertFalse(snapshot.symbolicLink(), "WOF compression is not a symbolic link");
	}

	private static void assertEqualsZero(int value, String command) {
		Assumptions.assumeTrue(value == 0, command + " failed with exit " + value);
	}
}
