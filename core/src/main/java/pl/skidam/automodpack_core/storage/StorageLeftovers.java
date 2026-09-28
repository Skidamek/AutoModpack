package pl.skidam.automodpack_core.storage;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.utils.ByteFormat;
import pl.skidam.automodpack_core.utils.DurableFiles;

/** Names the top-level entries of the automodpack folder that this layout does not own, so an updated install says what it left behind. */
public final class StorageLeftovers {

	// The v4 config predecessors and their migration .backup renames stay unowned on purpose: they are exactly what the warning is for.
	private static final Set<String> OWNED_ENTRIES = Set.of(
			name(StoragePaths.CLIENT_DIR),
			name(StoragePaths.RECOVERED_DIR),
			name(StoragePaths.HELPER_DIR),
			name(StoragePaths.SERVER_DIR),
			name(StoragePaths.CREDENTIALS_DIR),
			name(StoragePaths.HOST_MODPACK_DIR),
			name(StoragePaths.SELF_UPDATE_FILE),
			name(StoragePaths.SERVER_CONFIG_FILE),
			name(StoragePaths.CLIENT_CONFIG_FILE),
			name(StoragePaths.BOOTSTRAP_FILE),
			name(StoragePaths.BOOTSTRAP_EXPORT_FILE));

	private StorageLeftovers() {}

	/** Read-only nag. A weird filesystem must never cost the boot that is asking, so this cannot throw. */
	public static void warnAbout(Path automodpackDirectory) {
		try {
			warn(automodpackDirectory);
		} catch (IOException e) {
			LOGGER.error("Could not scan {} for leftovers from older versions", automodpackDirectory, e);
		}
	}

	private static void warn(Path automodpackDirectory) throws IOException {
		List<String> leftovers = leftovers(automodpackDirectory);
		if (leftovers.isEmpty()) return;

		long totalBytes = 0;
		List<String> descriptions = new ArrayList<>();
		for (String leftover : leftovers) {
			long bytes = sizeOf(automodpackDirectory.resolve(leftover));
			totalBytes += bytes;
			descriptions.add(leftover + " (" + ByteFormat.formatSize(bytes) + ")");
		}
		LOGGER.warn("Leftovers from an older AutoModpack are still using {} in {}: {}. This version does not read them and deleting them is safe.",
				ByteFormat.formatSize(totalBytes), automodpackDirectory, String.join(", ", descriptions));
	}

	/** Top-level entry names the current layout does not own, sorted. */
	static List<String> leftovers(Path automodpackDirectory) throws IOException {
		if (!Files.isDirectory(automodpackDirectory, LinkOption.NOFOLLOW_LINKS)) return List.of();
		Set<String> names = new TreeSet<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(automodpackDirectory)) {
			for (Path entry : entries) {
				String name = name(entry);
				if (isLeftover(name)) names.add(name);
			}
		}
		return List.copyOf(names);
	}

	static boolean isLeftover(String name) {
		return !OWNED_ENTRIES.contains(name) && !name.contains(DurableFiles.CORRUPT_ASIDE_MARKER);
	}

	private static long sizeOf(Path entry) {
		SizeCounter counter = new SizeCounter();
		try {
			Files.walkFileTree(entry, counter);
		} catch (IOException e) {
			// the name in the warning still carries the point
		}
		return counter.bytes;
	}

	private static String name(Path entry) {
		return entry.getFileName().toString();
	}

	private static final class SizeCounter extends SimpleFileVisitor<Path> {
		long bytes;

		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
			bytes += attributes.size();
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFileFailed(Path file, IOException e) {
			return FileVisitResult.CONTINUE;
		}
	}
}
