package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Extracts and loads the bundled Windows JNI library once per process. Any failure leaves it unloaded and returns false. */
public final class WindowsNatives {
	private static final Object LOCK = new Object();
	private static volatile boolean loaded;
	private static volatile String loadError = "not loaded";

	private WindowsNatives() {}

	public static boolean ensureLoaded() {
		if (loaded) return true;
		synchronized (LOCK) {
			if (!loaded) load();
			return loaded;
		}
	}

	public static String loadError() {
		return loadError;
	}

	private static void load() {
		if (PlatformUtils.operatingSystem() != PlatformUtils.OperatingSystem.WINDOWS) {
			loadError = "not Windows";
			return;
		}
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		if (!arch.equals("amd64") && !arch.equals("x86_64")) {
			loadError = "unsupported arch " + arch;
			return;
		}
		try (InputStream in = WindowsNatives.class.getResourceAsStream("/natives/windows-x86_64/win_file_stat.dll")) {
			if (in == null) {
				loadError = "missing resource /natives/windows-x86_64/win_file_stat.dll";
				return;
			}
			byte[] bytes = in.readAllBytes();
			if (bytes.length < 64 || bytes[0] != 'M' || bytes[1] != 'Z') {
				loadError = "resource is not a PE";
				return;
			}
			String sha1 = HashUtils.sha1(bytes);
			Path directory = Path.of(System.getProperty("java.io.tmpdir"));
			Path file = directory.resolve("win-file-stat-" + sha1 + ".dll");
			if (!dllMatches(file, sha1)) {
				Path part = directory.resolve("win-file-stat-" + sha1 + ".dll.part");
				Files.write(part, bytes);
				try {
					Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (Throwable moveFailed) {
					try {
						Files.deleteIfExists(part);
					} catch (Throwable ignored) {
					}
					if (!dllMatches(file, sha1)) throw moveFailed;
				}
			}
			if (!dllMatches(file, sha1)) {
				loadError = "extracted dll hash mismatch";
				return;
			}
			System.load(file.toAbsolutePath().toString());
			loaded = true;
			loadError = "loaded " + file;
		} catch (Throwable t) {
			loadError = t.toString();
			LOGGER.warn("Windows native library is unavailable: {}", loadError);
		}
	}

	private static boolean dllMatches(Path file, String sha1) {
		try {
			return Files.isRegularFile(file) && sha1.equals(HashUtils.sha1(Files.readAllBytes(file)));
		} catch (Throwable ignored) {
			return false;
		}
	}
}
