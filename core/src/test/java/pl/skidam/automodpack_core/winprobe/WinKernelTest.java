package pl.skidam.automodpack_core.winprobe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.PlatformUtils;
import pl.skidam.automodpack_core.utils.WindowsLockProbe;

class WinKernelTest {

	@TempDir
	Path tempDirectory;

	@Test
	void bootFailsClosedOffWindows() {
		if (PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS && PlatformUtils.isX8664()) {
			try (WinKernel kernel = WinKernel.boot()) {
				assertTrue(Files.isDirectory(kernel.root()), kernel.root().toString());
			}
			return;
		}
		WinProbeFailed failed = assertThrows(WinProbeFailed.class, WinKernel::boot);
		assertTrue(failed.getMessage().startsWith("not Windows") || failed.getMessage().startsWith("unsupported architecture"), failed.getMessage());
	}

	@Test
	void r1JavaHolderDoesNotTripHeld0() throws Exception {
		Path file = tempDirectory.resolve("r1.bin");
		Files.writeString(file, "r1", StandardCharsets.UTF_8);
		try (WinKernel kernel = WinKernel.boot()) {
			try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
				assertFalse(WindowsLockProbe.held(file), "a Java holder shares DELETE so held0 stays false");
			}
			assertTrue(Files.isDirectory(kernel.root()));
			return;
		} catch (WinProbeFailed failed) {
			assertTrue(failed.getMessage().startsWith("not Windows") || failed.getMessage().startsWith("unsupported architecture"), failed.getMessage());
		}
	}

	@Test
	void r2ForeignShareReadTripsHeld0() throws Exception {
		Path file = tempDirectory.resolve("r2.bin");
		Files.writeString(file, "r2", StandardCharsets.UTF_8);
		try (WinKernel kernel = WinKernel.boot()) {
			try (ReplayClient client = new ReplayClient()) {
				client.hold(file, ReplayClient.Open.SHARE_READ);
				assertTrue(WindowsLockProbe.held(file), "a foreign share=READ holder must refuse DELETE");
			}
			assertFalse(WindowsLockProbe.held(file), "the hold must clear when ReplayClient closes");
			return;
		} catch (WinProbeFailed failed) {
			assertTrue(failed.getMessage().startsWith("not Windows") || failed.getMessage().startsWith("unsupported architecture"), failed.getMessage());
		}
	}
}
