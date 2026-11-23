package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.winprobe.WinKernel;
import pl.skidam.automodpack_core.winprobe.WinProbeFailed;

class WindowsLockProbeTest {

	@TempDir
	Path tempDirectory;

	@Test
	void lockProbeNamesHoldersAndClears() throws Exception {
		Path file = tempDirectory.resolve("held.bin");
		Files.writeString(file, "lock-probe");
		try (WinKernel kernel = WinKernel.boot()) {
			assertNull(WindowsLockProbe.describeHeld(file), WindowsNatives.loadError());
			try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
				String receipt = WindowsLockProbe.describeHeld(file);
				assertNotNull(receipt, WindowsNatives.loadError());
				assertTrue(receipt.contains("PID " + ProcessHandle.current().pid()), receipt);
				assertTrue(receipt.contains("this game"), receipt);
			}
			assertNull(WindowsLockProbe.describeHeld(file), "the holder receipt should clear once the channel closes");
			assertEquals("could not tell which", WindowsLockProbe.describeHeld(tempDirectory), WindowsNatives.loadError());
			assertTrue(Files.isDirectory(kernel.root()));
			return;
		} catch (WinProbeFailed failed) {
			assertNull(WindowsLockProbe.describeHeld(file));
			assertTrue(failed.getMessage().startsWith("not Windows") || failed.getMessage().startsWith("unsupported architecture"), failed.getMessage());
		}
	}
}
