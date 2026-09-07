package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Whether this Windows machine opted into long paths. MSDN: with the {@code LongPathsEnabled} registry value set
 * under {@code HKLM\SYSTEM\CurrentControlSet\Control\FileSystem}, Win32 file APIs accept paths past classic
 * {@code MAX_PATH} up to the extended-length maximum (approximately 32,767 characters; name components stay 255).
 * The query runs once per process, and only when a path would actually be refused, so default installs never pay
 * for it. Any failure reads as disabled and the classic budget keeps protecting stock Windows.
 */
final class WindowsLongPaths {
	private static final String QUERY = "reg query HKLM\\SYSTEM\\CurrentControlSet\\Control\\FileSystem /v LongPathsEnabled";
	/** A local registry read answers in tens of milliseconds; two seconds is two orders of magnitude of slack. */
	private static final long QUERY_TIMEOUT_MS = 2000;

	private static volatile Boolean enabled;

	private WindowsLongPaths() {}

	static boolean areEnabled() {
		Boolean cached = enabled;
		if (cached != null) return cached;
		synchronized (WindowsLongPaths.class) {
			if (enabled == null) enabled = query();
			return enabled;
		}
	}

	private static boolean query() {
		try {
			Process process = new ProcessBuilder(QUERY.split(" ")).redirectErrorStream(true).start();
			// The output is a few hundred bytes, far under the pipe buffer, and reg never spawns children,
			// so waiting for exit before reading cannot deadlock and EOF after exit is immediate.
			if (!process.waitFor(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				return false;
			}
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return parseValue(output);
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** The value line reads {@code LongPathsEnabled    REG_DWORD    0x1}; the key is absent on pre-1607 Windows. */
	private static boolean parseValue(String output) {
		for (String line : output.split("\n")) {
			if (!line.contains("LongPathsEnabled")) continue;
			String[] tokens = line.trim().split("\\s+");
			if (tokens.length == 0) return false;
			try {
				return Integer.decode(tokens[tokens.length - 1]) == 1;
			} catch (NumberFormatException e) {
				return false;
			}
		}
		return false;
	}
}
