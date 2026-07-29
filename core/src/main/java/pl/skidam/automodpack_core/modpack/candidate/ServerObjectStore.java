package pl.skidam.automodpack_core.modpack.candidate;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

import pl.skidam.automodpack_core.utils.HashUtils;

/** Promotes verified candidate snapshots into the immutable server object directory. */
public final class ServerObjectStore {
	private final Path objectsDirectory;
	private final Path stagingDirectory;

	public ServerObjectStore(Path objectsDirectory, Path stagingDirectory) {
		this.objectsDirectory = Objects.requireNonNull(objectsDirectory).toAbsolutePath().normalize();
		this.stagingDirectory = Objects.requireNonNull(stagingDirectory).toAbsolutePath().normalize();
		if (this.objectsDirectory.startsWith(this.stagingDirectory) || this.stagingDirectory.startsWith(this.objectsDirectory))
			throw new IllegalArgumentException("Managed object and staging directories must be separate");
	}

	public NavigableMap<String, Path> promoteAll(NavigableMap<String, StagedObject> objects) throws IOException {
		ensureManagedDirectory(objectsDirectory, "immutable object");
		ensureManagedDirectory(stagingDirectory, "staging");
		TreeMap<String, Path> promoted = new TreeMap<>();
		for (StagedObject object : objects.values()) {
			validateStaged(object);
			Path destination = destination(object.sha1());
			promote(object, destination);
			promoted.put(object.sha1(), destination);
		}
		return Collections.unmodifiableNavigableMap(promoted);
	}

	private void promote(StagedObject object, Path destination) throws IOException {
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			verifyExisting(destination, object);
			object.delete();
			return;
		}
		verifyStaged(object);
		try {
			/*
			 * A hard link is the no-clobber commit: createLink fails if another object
			 * wins the destination race, unlike an atomic rename which may replace it.
			 * The source is our private staged snapshot, never the mutable operator file;
			 * deleting its name after linking therefore leaves the verified immutable inode.
			 */
			Files.createLink(destination, object.stagedPath());
			object.delete();
		} catch (FileAlreadyExistsException e) {
			verifyExisting(destination, object);
			object.delete();
		}
	}

	private Path destination(String sha1) throws IOException {
		Path destination = objectsDirectory.resolve(sha1).normalize();
		if (!destination.startsWith(objectsDirectory)) throw new IOException("Object path escapes immutable store: " + sha1);
		return destination;
	}

	private void validateStaged(StagedObject object) throws IOException {
		Path staged = object.stagedPath();
		if (!staged.startsWith(stagingDirectory) || staged.equals(stagingDirectory))
			throw new IOException("Staged object is outside the managed staging directory: " + staged);
		if (Files.isSymbolicLink(staged) || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Staged object is not a regular non-symlink file: " + staged);
		Path stagingReal = stagingDirectory.toRealPath();
		Path stagedReal = staged.toRealPath();
		if (!stagedReal.startsWith(stagingReal) || stagedReal.equals(stagingReal))
			throw new IOException("Staged object resolves outside the managed staging directory: " + staged);
	}

	private void verifyStaged(StagedObject object) throws IOException {
		force(object.stagedPath());
		if (!valid(object.stagedPath(), object)) throw new IOException("Staged object failed size/SHA-1 verification: " + object.stagedPath());
	}

	private static void verifyExisting(Path objectPath, StagedObject advertised) throws IOException {
		if (!valid(objectPath, advertised))
			throw new IOException("Refusing to replace corrupt immutable object " + objectPath + " for SHA-1 " + advertised.sha1());
	}

	private static boolean valid(Path path, StagedObject object) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
		return Files.size(path) == object.size() && object.sha1().equalsIgnoreCase(HashUtils.getHash(path));
	}

	private static void ensureManagedDirectory(Path directory, String description) throws IOException {
		if (Files.isSymbolicLink(directory)) throw new IOException("Managed " + description + " directory cannot be a symbolic link: " + directory);
		Files.createDirectories(directory);
		if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Managed " + description + " directory is not a regular directory: " + directory);
	}

	private static void force(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			channel.force(true);
		}
	}
}
