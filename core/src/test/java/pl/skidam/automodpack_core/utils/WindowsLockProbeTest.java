package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsLockProbeTest {

	@TempDir
	Path tempDirectory;

	@Test
	void lockProbeNamesHoldersAndClears() throws Exception {
		Path file = tempDirectory.resolve("held.bin");
		Files.writeString(file, "lock-probe");

		if (PlatformUtils.operatingSystem() != PlatformUtils.OperatingSystem.WINDOWS) {
			assertNull(WindowsLockProbe.describeHeld(file));
			return;
		}
		if (!PlatformUtils.isX8664()) return;

		// Nothing holds the file: the whole Restart Manager round trip must succeed and find nobody.
		assertNull(WindowsLockProbe.describeHeld(file), WindowsNatives.loadError());

		// This JVM is itself a holder: the probe must name this exact process among the holders.
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			String receipt = WindowsLockProbe.describeHeld(file);
			System.err.println("WindowsLockProbe: " + receipt + " (" + WindowsNatives.loadError() + ")");
			assertNotNull(receipt, WindowsNatives.loadError());
			assertTrue(receipt.contains("PID " + ProcessHandle.current().pid()), receipt);
			assertTrue(receipt.contains("this game"), receipt);
		}

		assertNull(WindowsLockProbe.describeHeld(file), "the holder receipt should clear once the channel closes");

		// A directory input runs the entry probe; either outcome is a valid receipt there.
		String directoryReceipt = WindowsLockProbe.describeHeld(tempDirectory);
		System.err.println("WindowsLockProbe: " + directoryReceipt);
		assertNotNull(directoryReceipt, WindowsNatives.loadError());
	}
}
