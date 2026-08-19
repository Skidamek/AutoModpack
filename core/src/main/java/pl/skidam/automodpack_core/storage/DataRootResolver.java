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
import java.util.Objects;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.StorageJsons;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.PlatformUtils;

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
			Path marker = gameRoot.resolve(StoragePaths.DATA_ROOT_MARKER_FILE).normalize();
			Path lockPath = gameRoot.resolve(StoragePaths.DATA_ROOT_LOCK_FILE).normalize();
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
		String ownerIdentity = computeOwnerIdentity(gameRoot);
		if (fields.ownerId == null || fields.ownerId.isBlank() || !ownerIdentity.equals(fields.ownerPathHash)) {
			fields.ownerId = UUID.randomUUID().toString();
			fields.ownerPathHash = ownerIdentity;
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
		fields.ownerPathHash = computeOwnerIdentity(gameRoot);
		fields.ownerPath = gameRoot.toString();
		ConfigTools.writeAtomic(marker, fields);
	}

	/** Identifies a game installation across symlink aliases without trusting a user-controlled path string. */
	private static String computeOwnerIdentity(Path gameRoot) throws IOException {
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
		return PlatformUtils.userDataDirectory().resolve("AutoModpack").resolve("data").toAbsolutePath().normalize();
	}
}
