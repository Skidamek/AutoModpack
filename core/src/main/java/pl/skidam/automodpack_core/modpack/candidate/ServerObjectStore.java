package pl.skidam.automodpack_core.modpack.candidate;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.ImmutableFilePublisher;
import pl.skidam.automodpack_core.utils.ImmutableFiles;

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
		FileTrees.createManagedDirectory(objectsDirectory, "immutable object directory");
		FileTrees.createManagedDirectory(stagingDirectory, "staging directory");
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
			ImmutableFiles.protect(destination);
			object.delete();
			return;
		}
		verifyStaged(object);
		ImmutableFilePublisher.publishFile(object.stagedPath(), destination, path -> verifyExisting(path, object));
		ImmutableFiles.protect(destination);
		object.delete();
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
		FileTrees.forceFile(object.stagedPath());
		if (!valid(object.stagedPath(), object)) throw new IOException("Staged object failed size/SHA-1 verification: " + object.stagedPath());
	}

	private static void verifyExisting(Path objectPath, StagedObject advertised) throws IOException {
		if (!valid(objectPath, advertised))
			throw new IOException("Refusing to replace corrupt immutable object " + objectPath + " for SHA-1 " + advertised.sha1());
	}

	private static boolean valid(Path path, StagedObject object) {
		return FileIntegrity.matches(path, object.size(), object.sha1());
	}

}
