package pl.skidam.automodpack_core.winprobe;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

import pl.skidam.automodpack_core.utils.PlatformUtils;
import pl.skidam.automodpack_core.utils.WindowsNatives;

/**
 * Boots only on a Windows x86-64 kernel with the bundled native loaded and a fixed NTFS volume.
 * Off Windows this throws; it never returns a degraded handle.
 */
public final class WinKernel implements AutoCloseable {
	private final Path root;

	private WinKernel(Path root) {
		this.root = root;
	}

	public static WinKernel boot() {
		if (PlatformUtils.operatingSystem() != PlatformUtils.OperatingSystem.WINDOWS) {
			throw new WinProbeFailed("not Windows: " + System.getProperty("os.name", ""));
		}
		if (!PlatformUtils.isX8664()) {
			throw new WinProbeFailed("unsupported architecture " + System.getProperty("os.arch", ""));
		}
		if (!WindowsNatives.ensureLoaded()) {
			throw new WinProbeFailed("native not loaded: " + WindowsNatives.loadError());
		}
		Path root = resolveRoot();
		try {
			Files.createDirectories(root);
			FileStore store = Files.getFileStore(root);
			String type = store.type() == null ? "" : store.type();
			if (!"NTFS".equalsIgnoreCase(type)) {
				throw new WinProbeFailed("volume is not fixed NTFS: " + type + " at " + root);
			}
		} catch (WinProbeFailed failed) {
			throw failed;
		} catch (IOException error) {
			throw new WinProbeFailed("cannot read volume for " + root + ": " + error);
		}
		return new WinKernel(root);
	}

	public Path root() {
		return root;
	}

	@Override
	public void close() {}

	static Path resolveRoot() {
		String override = System.getProperty("winprobe.root");
		if (override != null && !override.isBlank()) return Path.of(override);
		Path drive = Path.of("C:\\", "winprobe-" + ProcessHandle.current().pid());
		if (Files.exists(Path.of("C:\\"))) return drive;
		String local = System.getenv("LOCALAPPDATA");
		if (local != null && !local.isBlank()) return Path.of(local, "winprobe-" + ProcessHandle.current().pid());
		return Path.of(System.getProperty("java.io.tmpdir", "."), "winprobe-" + ProcessHandle.current().pid());
	}
}
