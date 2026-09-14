package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What, and where the platform can tell who, is holding a path open against a rename. Windows only: a directory's
 * entries are probed by taking DELETE access (the same right the update's blocked move needs) without moving
 * anything, and the bundled Restart Manager native names the processes holding the found paths. Any failure returns null.
 */
public final class WindowsLockProbe {
	private static final int MAX_HELD_PATHS = 5;
	private static final int MAX_PROBE_OPERATIONS = 512;
	private static final char SEPARATOR = '\u001F';

	private WindowsLockProbe() {}

	/**
	 * A compact receipt for a rename blocker: held child paths, each with its holding processes where Restart
	 * Manager can answer, or the folder-itself hint when probing finds nothing. Null when this is not Windows,
	 * the path is not a regular file or directory, or anything failed; never throws.
	 */
	public static String describeHeld(Path path) {
		if (path == null || PlatformUtils.operatingSystem() != PlatformUtils.OperatingSystem.WINDOWS) return null;
		try {
			path = path.toAbsolutePath();
			if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
				String processes = describeProcesses(path);
				return processes == null ? null : path.getFileName() + " is held by " + processes;
			}
			if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return null;
			List<Path> held = new ArrayList<>();
			probeChildren(path, path, 0, new int[]{MAX_PROBE_OPERATIONS}, held);
			if (held.isEmpty()) return "no specific file stays held; the folder itself was busy a moment ago (close File Explorer windows and programs using it)";
			StringBuilder receipt = new StringBuilder();
			for (Path heldPath : held) {
				if (!receipt.isEmpty()) receipt.append("; ");
				receipt.append(path.relativize(heldPath));
				String processes = describeProcesses(heldPath);
				if (processes != null) receipt.append(" held by ").append(processes);
			}
			return receipt.toString();
		} catch (Throwable t) {
			LOGGER.debug("Lock probe failed for {}", path, t);
			return null;
		}
	}

	/** The holding process names of one path per the bundled Restart Manager native, or null when it cannot answer. */
	private static String describeProcesses(Path path) {
		if (!WindowsNatives.ensureLoaded()) return null;
		String raw = describe0(path.toString());
		if (raw == null || raw.isBlank()) return null;
		String[] fields = raw.split(String.valueOf(SEPARATOR), -1);
		long self = ProcessHandle.current().pid();
		StringBuilder names = new StringBuilder();
		for (int i = 0; i + 1 < fields.length; i += 2) {
			if (names.length() > 0) names.append(", ");
			names.append(fields[i]).append(" (PID ").append(fields[i + 1]);
			if (isSelf(fields[i + 1], self)) names.append(", this game");
			names.append(')');
		}
		return names.isEmpty() ? null : names.toString();
	}

	private static boolean isSelf(String pid, long self) {
		try {
			return Long.parseLong(pid) == self;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/**
	 * Asks of each entry of {@code directory} whether a rename would be denied, recursing one level into denied
	 * subdirectories, and records the denied leaves relative to {@code root}.
	 */
	private static void probeChildren(Path root, Path directory, int depth, int[] remainingOperations, List<Path> held) throws IOException {
		List<Path> children;
		try (var entries = Files.list(directory)) {
			children = entries.toList();
		}
		for (Path child : children) {
			if (remainingOperations[0] <= 0 || held.size() >= MAX_HELD_PATHS) return;
			remainingOperations[0]--;
			if (heldAgainstRename(child)) {
				if (depth < 1 && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) probeChildren(root, child, depth + 1, remainingOperations, held);
				else held.add(child);
			}
		}
	}

	/** Whether taking DELETE access on the entry is denied; the same right a rename needs, without moving the file. */
	private static boolean heldAgainstRename(Path child) {
		return WindowsNatives.ensureLoaded() && held0(child.toString());
	}

	/** The bundled Windows native: {@code name<SEPARATOR>pid} pairs of the processes holding path open, or null. */
	private static native String describe0(String path);

	/** The bundled Windows native: true when DELETE access on the path is denied. */
	private static native boolean held0(String path);
}
