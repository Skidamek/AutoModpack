package pl.skidam.automodpack_core.utils.cache;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.PlatformUtils;

/** Optional Windows NTFS ChangeTime and file index. Any load or read failure returns null. */
final class WindowsFileStat {
	private static final long WINDOWS_EPOCH_IN_100NS = -116444736000000000L;
	private static final ThreadLocal<long[]> RAW = ThreadLocal.withInitial(() -> new long[3]);
	private static volatile String loadError = "not loaded";
	private static final AtomicBoolean AVAILABLE = new AtomicBoolean(loadLibrary());

	record Snapshot(long changeTimeNanos, String fileKey) {}

	private WindowsFileStat() {}

	static Snapshot read(Path path) {
		if (!AVAILABLE.get() || path == null) return null;
		try {
			long[] raw = RAW.get();
			if (!read0(path.toAbsolutePath().toString(), raw)) return null;
			long changeTimeNanos = fileTimeToNanos(raw[0]);
			if (changeTimeNanos == Long.MIN_VALUE) return null;
			return new Snapshot(changeTimeNanos, raw[1] + ":" + raw[2]);
		} catch (Throwable t) {
			AVAILABLE.set(false);
			loadError = t.toString();
			return null;
		}
	}

	private static native boolean read0(String path, long[] out);

	private static long fileTimeToNanos(long time100ns) {
		try {
			return Math.multiplyExact(Math.addExact(time100ns, WINDOWS_EPOCH_IN_100NS), 100L);
		} catch (ArithmeticException e) {
			return Long.MIN_VALUE;
		}
	}

	static String loadError() {
		return loadError;
	}

	private static boolean loadLibrary() {
		if (PlatformUtils.operatingSystem() != PlatformUtils.OperatingSystem.WINDOWS) {
			loadError = "not Windows";
			return false;
		}
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		if (!arch.equals("amd64") && !arch.equals("x86_64")) {
			loadError = "unsupported arch " + arch;
			return false;
		}
		try (InputStream in = WindowsFileStat.class.getResourceAsStream("/natives/windows-x86_64/win_file_stat.dll")) {
			if (in == null) {
				loadError = "missing resource /natives/windows-x86_64/win_file_stat.dll";
				return false;
			}
			byte[] bytes = in.readAllBytes();
			if (bytes.length < 64 || bytes[0] != 'M' || bytes[1] != 'Z') {
				loadError = "resource is not a PE";
				return false;
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
				return false;
			}
			System.load(file.toAbsolutePath().toString());
			loadError = "loaded " + file;
			return true;
		} catch (Throwable t) {
			loadError = t.toString();
			LOGGER.warn("Windows NTFS stat native is unavailable: {}", loadError);
			return false;
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
