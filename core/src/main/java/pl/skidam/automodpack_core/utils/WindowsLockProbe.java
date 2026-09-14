package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * What, and where the platform can tell who, is holding a path open against a rename. Windows only: a directory's
 * entries are probed with a self-reverting atomic rename (the same delete access the update's blocked move needs),
 * and the bundled Restart Manager native names the processes holding the found paths. Any failure returns null.
 */
public final class WindowsLockProbe {
	private static final String PROBE_SUFFIX = ".amlockprobe";
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
			String name = child.getFileName().toString();
			if (name.endsWith(PROBE_SUFFIX)) {
				// A leftover of a probe that died between rename and revert; its content drift heals through the next update replan.
				Path original = directory.resolve(name.substring(0, name.length() - PROBE_SUFFIX.length()));
				if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS)) restore(child, original);
				continue;
			}
			if (heldByRename(child, directory.resolve(name + PROBE_SUFFIX))) {
				if (depth < 1 && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) probeChildren(root, child, depth + 1, remainingOperations, held);
				else held.add(child);
			}
		}
	}

	/** Whether renaming the entry is denied; the probe renames it to a sibling name and immediately renames it back. */
	private static boolean heldByRename(Path child, Path temp) throws IOException {
		try {
			Files.move(child, temp, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			return true;
		}
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				Files.move(temp, child, StandardCopyOption.ATOMIC_MOVE);
				return false;
			} catch (IOException retry) {
				try {
					Thread.sleep(50);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		LOGGER.error("Lock probe could not rename {} back from {}; the next update replan heals the drift", child, temp);
		return false;
	}

	private static void restore(Path temp, Path original) {
		try {
			Files.move(temp, original, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOGGER.error("Lock probe could not restore {} from {}; the next update replan heals the drift", original, temp);
		}
	}

	/** The bundled Windows native: {@code name<SEPARATOR>pid} pairs of the processes holding path open, or null. */
	private static native String describe0(String path);
}
