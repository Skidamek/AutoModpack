package pl.skidam.automodpack_core.utils.cache;

import java.nio.file.Path;

import pl.skidam.automodpack_core.utils.WindowsNatives;

/** Optional Windows NTFS ChangeTime and file index. Any load or read failure returns null. */
final class WindowsFileStat {
	private static volatile String loadError = "not loaded";

	record Snapshot(long changeTimeNanos, String fileKey) {}

	private WindowsFileStat() {}

	static Snapshot read(Path path) {
		if (!WindowsNatives.ensureLoaded() || path == null) return null;
		try {
			long[] raw = RAW.get();
			if (!read0(path.toAbsolutePath().toString(), raw)) return null;
			long changeTimeNanos = fileTimeToNanos(raw[0]);
			if (changeTimeNanos == Long.MIN_VALUE) return null;
			return new Snapshot(changeTimeNanos, raw[1] + ":" + raw[2]);
		} catch (Throwable t) {
			loadError = t.toString();
			return null;
		}
	}

	private static native boolean read0(String path, long[] out);

	private static final ThreadLocal<long[]> RAW = ThreadLocal.withInitial(() -> new long[3]);

	private static long fileTimeToNanos(long time100ns) {
		try {
			return Math.multiplyExact(Math.addExact(time100ns, WINDOWS_EPOCH_IN_100NS), 100L);
		} catch (ArithmeticException e) {
			return Long.MIN_VALUE;
		}
	}

	static String loadError() {
		return WindowsNatives.loadError();
	}

	private static final long WINDOWS_EPOCH_IN_100NS = -116444736000000000L;
}
