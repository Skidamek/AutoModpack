package pl.skidam.automodpack_core.storage;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.PlatformUtils;

/** Resolves the one shared-or-local AutoModpack data root for an instance. */
public final class DataRootResolver {
	public record Layout(Path root) {
		public Layout {
			root = Objects.requireNonNull(root, "data root").toAbsolutePath().normalize();
		}

		public Path objectsDirectory() {
			return root.resolve("objects").normalize();
		}

		public Path objectFile(String sha1) {
			return DataRootResolver.objectFile(objectsDirectory(), sha1);
		}

		public Path fileMetadataDirectory() {
			return root.resolve("file-metadata").normalize();
		}

		public Path modMetadataDirectory() {
			return root.resolve("mod-metadata").normalize();
		}

		public Path platformMetadataDirectory() {
			return root.resolve("platform-metadata").normalize();
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

	public record Location(Path root, String ownerId, Path ownerPath) {
		public Location {
			root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
			ownerId = HashUtils.normalizeSha1(Objects.requireNonNull(ownerId, "owner ID"));
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
			Path root = selectRoot(gameRoot);
			String localFailure = probe(root);
			if (localFailure != null) throw new IOException("AutoModpack data storage is not writable: " + localFailure);
			return new Location(root, computeOwnerIdentity(gameRoot), gameRoot);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot resolve AutoModpack data storage for " + requestedRoot, e);
		}
	}

	/** Git-style content-addressed path {@code objects/<aa>/<rest-of-sha1>}. */
	public static Path objectFile(Path objectsDirectory, String sha1) {
		String hash = HashUtils.normalizeSha1(sha1);
		Path root = Objects.requireNonNull(objectsDirectory, "objects directory").toAbsolutePath().normalize();
		Path file = root.resolve(hash.substring(0, 2)).resolve(hash.substring(2)).normalize();
		if (!file.startsWith(root) || !root.equals(file.getParent().getParent())) throw new IllegalArgumentException("Object path escaped the object store: " + sha1);
		return file;
	}

	public static boolean isObjectFile(Path objectsDirectory, Path file) {
		return objectHash(objectsDirectory, file) != null;
	}

	/** Reconstructs the SHA-1 from a Git-style object path, or {@code null} if the path is not an object file. */
	public static String objectHash(Path objectsDirectory, Path file) {
		if (file == null) return null;
		Path root = Objects.requireNonNull(objectsDirectory, "objects directory").toAbsolutePath().normalize();
		Path normalized = file.toAbsolutePath().normalize();
		Path shard = normalized.getParent();
		if (shard == null || !root.equals(shard.getParent())) return null;
		String prefix = shard.getFileName().toString();
		String rest = normalized.getFileName().toString();
		if (prefix.length() != 2 || rest.length() != HashUtils.SHA1_HEX_LENGTH - 2) return null;
		String hash = prefix + rest;
		if (!HashUtils.isCanonicalSha1(hash)) return null;
		return objectFile(root, hash).equals(normalized) ? hash : null;
	}

	private static Path canonicalGameRoot(Path requestedRoot) throws IOException {
		Files.createDirectories(requestedRoot);
		Path realRoot = requestedRoot.toRealPath();
		if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Game directory is not a directory: " + requestedRoot);
		return realRoot;
	}

	private static void createLocalDataDirectory(Path gameRoot, Path directory) throws IOException {
		Files.createDirectories(directory);
		if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("AutoModpack data directory is not a regular directory: " + directory);
		Path realDirectory = directory.toRealPath();
		if (!gameRoot.equals(realDirectory.getParent())) throw new IOException("AutoModpack data directory resolves outside the game directory: " + directory);
	}

	private static Path selectRoot(Path gameRoot) throws IOException {
		Path configured = configuredRoot(gameRoot);
		if (configured != null) {
			String failure = probe(configured);
			if (failure != null) throw new IOException("Configured AutoModpack data root is unusable: " + failure);
			return configured;
		}
		Path sharedRoot = platformDataRoot();
		String sharedFailure = probe(sharedRoot);
		if (sharedFailure == null) return sharedRoot;
		LOGGER.warn("Shared AutoModpack data root {} is unusable ({}); falling back to instance-local storage", sharedRoot, sharedFailure);
		return gameRoot.resolve(StoragePaths.LOCAL_DATA_DIR).normalize();
	}

	private static Path configuredRoot(Path gameRoot) {
		String value = firstNonBlank(System.getProperty(StoragePaths.DATA_ROOT_PROPERTY), System.getenv(StoragePaths.DATA_ROOT_ENV));
		if (value == null) return null;
		Path configured = Path.of(value);
		return (configured.isAbsolute() ? configured : gameRoot.resolve(configured)).toAbsolutePath().normalize();
	}

	private static String firstNonBlank(String property, String env) {
		if (property != null && !property.isBlank()) return property;
		if (env != null && !env.isBlank()) return env;
		return null;
	}

	/** Identifies a game installation across symlink aliases without trusting a user-controlled path string. */
	private static String computeOwnerIdentity(Path gameRoot) throws IOException {
		Path realRoot = gameRoot.toRealPath();
		Object fileKey = Files.readAttributes(realRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
		return HashUtils.sha1(fileKey == null ? "real-path\n" + realRoot : "file-key\n" + fileKey);
	}

	/** Returns null when the root is usable, otherwise a one-line reason it is not. */
	private static String probe(Path root) {
		Path normalized = root.toAbsolutePath().normalize();
		if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(normalized)) return "symbolic link";
		try {
			Files.createDirectories(normalized);
			if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) return "not a directory";
			Path probe = Files.createTempFile(normalized, ".write-probe-", ".tmp");
			Files.writeString(probe, "AutoModpack\n", StandardCharsets.UTF_8);
			Files.deleteIfExists(probe);
			return null;
		} catch (IOException | RuntimeException e) {
			return e.toString();
		}
	}

	private static Path platformDataRoot() {
		return PlatformUtils.userDataDirectory().resolve("automodpack").resolve("data").toAbsolutePath().normalize();
	}
}
