package pl.skidam.automodpack_core.utils.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.winprobe.WinKernel;
import pl.skidam.automodpack_core.winprobe.WinProbeFailed;

/** The reparse-tag classifier and, on a Windows kernel, the tags compact.exe writes. */
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
		try (WinKernel kernel = WinKernel.boot()) {
			Path file = temporaryDirectory.resolve("compacted.bin");
			Process create = new ProcessBuilder("fsutil", "file", "createnew", file.toString(), "2097152").redirectErrorStream(true).start();
			assertTrue(create.waitFor() == 0, "fsutil file createnew");
			Process compact = new ProcessBuilder("compact", "/c", "/f", file.toString()).redirectErrorStream(true).start();
			byte[] output = compact.getInputStream().readAllBytes();
			assertTrue(compact.waitFor() == 0, "compact.exe could not compress this file: " + new String(output, StandardCharsets.UTF_8));
			FileCache.StatSnapshot snapshot = WindowsFileStat.statSnapshot(file);
			assertTrue(snapshot != null, "the Windows native stat is unavailable: " + WindowsFileStat.loadError());
			assertTrue(snapshot.regularFile(), "a WOF-compressed file is still a regular file");
			assertFalse(snapshot.junction(), "WOF compression is not a junction");
			assertFalse(snapshot.symbolicLink(), "WOF compression is not a symbolic link");
			assertTrue(kernel.root() != null);
			return;
		} catch (WinProbeFailed failed) {
			assertTrue(failed.getMessage().startsWith("not Windows") || failed.getMessage().startsWith("unsupported architecture"), failed.getMessage());
		}
	}
}
