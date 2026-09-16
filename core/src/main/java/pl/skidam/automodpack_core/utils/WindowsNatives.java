package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Extracts and loads the bundled Windows JNI library once per process. Any failure leaves it unloaded and returns false. */
public final class WindowsNatives {
	private static final String RESOURCE = "/natives/windows-x86_64/win_natives.dll";
	private static final Object LOCK = new Object();
	private static volatile boolean attempted;
	private static volatile boolean loaded;
	private static volatile String loadError = "not loaded";

	private WindowsNatives() {}

	public static boolean ensureLoaded() {
		if (loaded) return true;
		synchronized (LOCK) {
			// One attempt per process: a machine that cannot load it (foreign architecture, locked-down temp) must not re-warn per stat call.
			if (!attempted) {
				attempted = true;
				load();
			}
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
		if (!PlatformUtils.isX8664()) {
			loadError = "unsupported architecture " + System.getProperty("os.arch", "");
			return;
		}
		try (InputStream in = WindowsNatives.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				loadError = "missing resource " + RESOURCE;
				return;
			}
			byte[] bytes = in.readAllBytes();
			if (bytes.length < 64 || bytes[0] != 'M' || bytes[1] != 'Z') {
				loadError = "resource is not a PE";
				return;
			}
			String sha1 = HashUtils.sha1(bytes);
			Path directory = Path.of(System.getProperty("java.io.tmpdir"));
			Path file = directory.resolve("win-natives-" + sha1 + ".dll");
			if (!dllMatches(file, sha1)) {
				Path part = directory.resolve("win-natives-" + sha1 + ".dll.part");
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
