package pl.skidam.automodpack_core.utils.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Explicit maintenance for the client CAS. Transaction workspaces are separate and are never swept as CAS staging. */
public final class ClientObjectStore {
	private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");
	private final Path objectsDirectory;

	public record CollectionResult(int objectsBefore, int objectsDeleted) {
		public int objectsAfter() {
			return objectsBefore - objectsDeleted;
		}
	}

	public ClientObjectStore(Path objectsDirectory) {
		this.objectsDirectory = Objects.requireNonNull(objectsDirectory).toAbsolutePath().normalize();
	}

	public CollectionResult collect(Set<String> retainedHashes) throws IOException {
		Set<String> retained = new HashSet<>();
		for (String hash : retainedHashes) retained.add(normalizeHash(hash));
		ensureManagedDirectory(objectsDirectory);
		int objectsBefore = 0;
		int objectsDeleted = 0;
		try (Stream<Path> files = Files.list(objectsDirectory)) {
			for (Path file : files.toList()) {
				if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
				String fileName = file.getFileName().toString();
				if (!SHA1.matcher(fileName).matches()) continue;
				objectsBefore++;
				if (!retained.contains(fileName.toLowerCase(Locale.ROOT))) {
					Files.deleteIfExists(file);
					objectsDeleted++;
				}
			}
		}
		return new CollectionResult(objectsBefore, objectsDeleted);
	}

	public static String normalizeHash(String sha1) {
		if (sha1 == null || !SHA1.matcher(sha1).matches()) throw new IllegalArgumentException("Invalid client object SHA-1");
		return sha1.toLowerCase(Locale.ROOT);
	}

	private static void ensureManagedDirectory(Path directory) throws IOException {
		if (Files.isSymbolicLink(directory)) throw new IOException("Client object directory cannot be a symbolic link: " + directory);
		Files.createDirectories(directory);
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client object directory is not a directory: " + directory);
	}
}
