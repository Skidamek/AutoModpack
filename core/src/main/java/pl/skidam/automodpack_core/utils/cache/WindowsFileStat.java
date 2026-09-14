package pl.skidam.automodpack_core.utils.cache;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

import pl.skidam.automodpack_core.utils.WindowsNatives;

/** Optional full Windows NTFS stat - timestamps, size, attributes, volume serial, file index, and reparse tag - from one native call. Any load or read failure returns null. */
final class WindowsFileStat {
	// raw[6] carries the Windows file attributes; raw[7] the reparse tag, zero without a reparse point.
	private static final int FILE_ATTRIBUTE_DEVICE = 0x40;
	private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;
	private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;
	private static final long IO_REPARSE_TAG_SYMLINK = 0xA000000CL;
	private static final long IO_REPARSE_TAG_DEDUP = 0x80000013L;
	private static final long IO_REPARSE_TAG_CLOUD = 0x80000000L;
	private static final long IO_REPARSE_TAG_CLOUD_MASK = 0x0F000000L;

	record Snapshot(long changeTimeNanos, String fileKey) {}

	private WindowsFileStat() {}

	/** ChangeTime and file identity, the complement to attributes Java already read. */
	static Snapshot read(Path path) {
		long[] raw = stat(path);
		if (raw == null) return null;
		long changeTimeNanos = fileTimeToNanos(raw[0]);
		if (changeTimeNanos == Long.MIN_VALUE) return null;
		return new Snapshot(changeTimeNanos, raw[4] + ":" + raw[5]);
	}

	/**
	 * The whole {@link FileCache.StatSnapshot} from the one native stat, or null when the native is unavailable or the file cannot be answered. The classification mirrors
	 * OpenJDK's {@code WindowsFileAttributes}: a reparse point is a symlink only when its tag says so, and dedup and cloud-placeholder files stay regular files.
	 */
	static FileCache.StatSnapshot statSnapshot(Path path) {
		long[] raw = stat(path);
		if (raw == null) return null;
		long changeTimeNanos = fileTimeToNanos(raw[0]);
		FileTime lastModified = fileTime(raw[1]);
		FileTime creation = fileTime(raw[2]);
		if (changeTimeNanos == Long.MIN_VALUE || lastModified == null || creation == null) return null;
		boolean directory = (raw[6] & FILE_ATTRIBUTE_DIRECTORY) != 0;
		boolean reparse = (raw[6] & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
		boolean symbolicLink = reparse && raw[7] == IO_REPARSE_TAG_SYMLINK;
		boolean regularFile = !symbolicLink && !directory && (raw[6] & FILE_ATTRIBUTE_DEVICE) == 0 && (!reparse || raw[7] == IO_REPARSE_TAG_DEDUP || (raw[7] & ~IO_REPARSE_TAG_CLOUD_MASK) == IO_REPARSE_TAG_CLOUD);
		return new FileCache.StatSnapshot(lastModified, creation, changeTimeNanos, raw[3], raw[4] + ":" + raw[5], regularFile, symbolicLink);
	}

	private static long[] stat(Path path) {
		if (!WindowsNatives.ensureLoaded() || path == null) return null;
		try {
			long[] raw = RAW.get();
			if (!read0(path.toAbsolutePath().normalize().toString(), raw)) return null;
			return raw;
		} catch (Throwable t) {
			return null;
		}
	}

	private static native boolean read0(String path, long[] out);

	private static final ThreadLocal<long[]> RAW = ThreadLocal.withInitial(() -> new long[8]);

	private static FileTime fileTime(long time100ns) {
		long nanos = fileTimeToNanos(time100ns);
		return nanos == Long.MIN_VALUE ? null : FileTime.from(nanos, TimeUnit.NANOSECONDS);
	}

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
