package pl.skidam.automodpack_core.modpack.candidate;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.ImmutableFilePublisher;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

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
		return promoteAll(objects, null);
	}

	public NavigableMap<String, Path> promoteAll(NavigableMap<String, StagedObject> objects, FileMetadataCache cache) throws IOException {
		FileTrees.createManagedDirectory(objectsDirectory, "immutable object directory");
		FileTrees.createManagedDirectory(stagingDirectory, "staging directory");
		TreeMap<String, Path> promoted = new TreeMap<>();
		for (StagedObject object : objects.values()) {
			validateStaged(object);
			Path destination = destination(object.sha1());
			promote(object, destination, cache);
			promoted.put(object.sha1(), destination);
		}
		return Collections.unmodifiableNavigableMap(promoted);
	}

	private void promote(StagedObject object, Path destination, FileMetadataCache cache) throws IOException {
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			ImmutableFiles.protect(destination);
			if (FileIntegrity.matchesNamed(destination, object.size(), object.sha1(), cache)) {
				object.delete();
				if (cache != null) cache.overwriteCache(destination, object.sha1());
				return;
			}
			LOGGER.warn("Immutable object {} no longer matches its Git-stat tripwire; replacing from a fresh source snapshot", destination);
			ImmutableFiles.deleteIfExists(destination);
		}
		requireStagedSize(object);
		FileTrees.forceFile(object.stagedPath());
		ImmutableFilePublisher.publishFile(object.stagedPath(), destination, path -> requireSize(path, object));
		ImmutableFiles.protect(destination);
		object.delete();
		if (cache != null) cache.overwriteCache(destination, object.sha1());
	}

	private Path destination(String sha1) throws IOException {
		try {
			return DataRootResolver.objectFile(objectsDirectory, sha1);
		} catch (IllegalArgumentException e) {
			throw new IOException("Object path escapes immutable store: " + sha1, e);
		}
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

	private static void requireStagedSize(StagedObject object) throws IOException {
		requireSize(object.stagedPath(), object);
	}

	private static void requireSize(Path path, StagedObject object) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != object.size())
			throw new IOException("Staged object failed size verification: " + path);
	}
}
