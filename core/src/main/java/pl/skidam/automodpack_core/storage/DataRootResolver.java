package pl.skidam.automodpack_core.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.StorageJsons;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Resolves the one shared-or-local AutoModpack data root for an instance. */
public final class DataRootResolver {
	private static final Object RESOLUTION_LOCK = new Object();
	public record Layout(Path root) {
		public Layout {
			root = Objects.requireNonNull(root, "data root").toAbsolutePath().normalize();
		}

		public Path objectsDirectory() {
			return root.resolve("objects").normalize();
		}

		public Path fileMetadataDirectory() {
			return root.resolve("file-metadata").normalize();
		}

		public Path modMetadataDirectory() {
			return root.resolve("mod-metadata").normalize();
		}

		public Path packsDirectory() {
			return root.resolve("packs").normalize();
		}

		public Path knownHostsFile() {
			return root.resolve("known-hosts.json").normalize();
		}

		public Path knownHostsLockFile() {
			return root.resolve("known-hosts.json.lock").normalize();
		}

		public Path objectOwnersDirectory() {
			return root.resolve("object-owners").normalize();
		}

		public Path objectOwnershipLockFile() {
			return root.resolve("object-owners.lock").normalize();
		}
	}

	public record Location(Path root, boolean shared, String ownerId, Path ownerPath) {
		public Location {
			root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
			ownerId = UUID.fromString(Objects.requireNonNull(ownerId, "owner ID")).toString();
			ownerPath = Objects.requireNonNull(ownerPath, "owner path").toAbsolutePath().normalize();
		}

		public Layout layout() {
			return new Layout(root);
		}
	}

	private DataRootResolver() {}

	public static Location resolve(Path gameDirectory) {
		Path requestedRoot = Objects.requireNonNull(gameDirectory, "game directory").toAbsolutePath().normalize();
		try {
			Path gameRoot = canonicalGameRoot(requestedRoot);
			Path automodpackDirectory = gameRoot.resolve(StoragePaths.AUTOMODPACK_DIR).normalize();
			createLocalDataDirectory(gameRoot, automodpackDirectory);
			Path marker = automodpackDirectory.resolve("data-root.json").normalize();
			Path lockPath = automodpackDirectory.resolve("data-root.lock").normalize();
			synchronized (RESOLUTION_LOCK) {
				try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); FileLock ignored = channel.lock()) {
					validateLocalDataDirectory(gameRoot, automodpackDirectory);
					if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return loadPinned(marker, gameRoot);
					Path sharedRoot = platformDataRoot();
					if (probe(sharedRoot)) {
						Location location = new Location(sharedRoot, true, UUID.randomUUID().toString(), gameRoot);
						writePinned(marker, location, gameRoot);
						return location;
					}
					Path fallback = automodpackDirectory.resolve("data").normalize();
					if (!probe(fallback)) throw new IOException("Neither shared nor local AutoModpack data storage is writable");
					Location location = new Location(fallback, false, UUID.randomUUID().toString(), gameRoot);
					writePinned(marker, location, gameRoot);
					return location;
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Cannot resolve AutoModpack data storage for " + requestedRoot, e);
		}
	}

	private static Path canonicalGameRoot(Path requestedRoot) throws IOException {
		Files.createDirectories(requestedRoot);
		Path realRoot = requestedRoot.toRealPath();
		if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Game directory is not a directory: " + requestedRoot);
		return realRoot;
	}

	private static void createLocalDataDirectory(Path gameRoot, Path directory) throws IOException {
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
		validateLocalDataDirectory(gameRoot, directory);
	}

	private static void validateLocalDataDirectory(Path gameRoot, Path directory) throws IOException {
		if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("AutoModpack data directory is not a regular directory: " + directory);
		Path realDirectory = directory.toRealPath();
		if (!gameRoot.equals(realDirectory.getParent())) throw new IOException("AutoModpack data directory resolves outside the game directory: " + directory);
	}

	private static Location loadPinned(Path marker, Path gameRoot) throws IOException {
		if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("AutoModpack data-root marker is not a regular file: " + marker);
		StorageJsons.DataRootFields fields = ConfigTools.read(marker, StorageJsons.DataRootFields.class).orElseThrow(() -> new IOException("AutoModpack data-root marker is empty"));
		if (fields.root == null || fields.root.isBlank()) throw new IOException("AutoModpack data-root marker has no root");
		Path root = Path.of(fields.root).toAbsolutePath().normalize();
		if (!probe(root)) throw new IOException("Pinned AutoModpack data root is unavailable: " + root);
		String ownerPathHash = ownerPathHash(gameRoot);
		if (fields.ownerId == null || fields.ownerId.isBlank() || !ownerPathHash.equals(fields.ownerPathHash)) {
			fields.ownerId = UUID.randomUUID().toString();
			fields.ownerPathHash = ownerPathHash;
			fields.ownerPath = gameRoot.toString();
			ConfigTools.writeAtomic(marker, fields);
		} else if (!gameRoot.toString().equals(fields.ownerPath)) {
			fields.ownerPath = gameRoot.toString();
			ConfigTools.writeAtomic(marker, fields);
		}
		return new Location(root, fields.shared, fields.ownerId, gameRoot);
	}

	private static void writePinned(Path marker, Location location, Path gameRoot) throws IOException {
		StorageJsons.DataRootFields fields = new StorageJsons.DataRootFields();
		fields.root = location.root().toString();
		fields.shared = location.shared();
		fields.ownerId = location.ownerId();
		fields.ownerPathHash = ownerPathHash(gameRoot);
		fields.ownerPath = gameRoot.toString();
		ConfigTools.writeAtomic(marker, fields);
	}

	private static String ownerPathHash(Path gameRoot) throws IOException {
		Path realRoot = gameRoot.toRealPath();
		Object fileKey = Files.readAttributes(realRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
		return HashUtils.sha1(fileKey == null ? "real-path\n" + realRoot : "file-key\n" + fileKey);
	}

	private static boolean probe(Path root) {
		try {
			Path normalized = root.toAbsolutePath().normalize();
			if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(normalized)) return false;
			Files.createDirectories(normalized);
			if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) return false;
			Path probe = Files.createTempFile(normalized, ".write-probe-", ".tmp");
			Files.writeString(probe, "AutoModpack\n", StandardCharsets.UTF_8);
			Files.deleteIfExists(probe);
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static Path platformDataRoot() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		Path base;
		if (os.contains("win")) {
			String localAppData = System.getenv("LOCALAPPDATA");
			base = localAppData == null || localAppData.isBlank() ? Path.of(System.getProperty("user.home"), "AppData", "Local") : Path.of(localAppData);
		} else if (os.contains("mac") || os.contains("darwin")) {
			base = Path.of(System.getProperty("user.home"), "Library", "Application Support");
		} else {
			String xdgDataHome = System.getenv("XDG_DATA_HOME");
			base = xdgDataHome == null || xdgDataHome.isBlank() ? Path.of(System.getProperty("user.home"), ".local", "share") : Path.of(xdgDataHome);
		}
		return base.resolve("AutoModpack").resolve("data").toAbsolutePath().normalize();
	}
}
