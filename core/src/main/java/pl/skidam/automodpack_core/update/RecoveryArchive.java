package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

/** A manifest of user-recoverable paths whose bytes are copied into an independent recovery archive. */
public final class RecoveryArchive {
	private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");
	private static final Comparator<Jsons.ClientRecoveryArchiveFields.EntryFields> ENTRY_ORDER = Comparator
			.comparing((Jsons.ClientRecoveryArchiveFields.EntryFields entry) -> entry.logicalPath)
			.thenComparing(entry -> entry.sha1).thenComparingLong(entry -> entry.size);

	private RecoveryArchive() {}

	public static Path archive(Path storeDirectory, Path recoveryDirectory, String logicalPath, String sha1, long size) throws IOException {
		return archive(storeDirectory, recoveryDirectory, logicalPath, sha1, size, "", Instant.now().toString());
	}

	public static Path archive(Path storeDirectory, Path recoveryDirectory, String logicalPath, String sha1, long size, String sourceGenerationId, String preservedAt) throws IOException {
		Path storeRoot = requireDirectory(storeDirectory, "shared object store");
		Path archiveRoot = requireArchiveRoot(recoveryDirectory);
		String normalizedPath = requirePath(logicalPath);
		String normalizedHash = requireHash(sha1);
		String normalizedSourceGenerationId = requireOptionalGeneration(sourceGenerationId);
		String normalizedPreservedAt = requireInstant(preservedAt);
		if (size < 0) throw new IOException("Recovery object size is invalid");

		Path archiveObjects = archiveRoot.resolve("objects").normalize();
		Path archivedObject = archiveObjects.resolve(normalizedHash).normalize();
		validateNoSymbolicLinkDescendants(archiveRoot, archivedObject);
		if (!SmartFileUtils.isValidFile(archivedObject, size, normalizedHash)) {
			Path object = storeRoot.resolve(normalizedHash).normalize();
			validateNoSymbolicLinkDescendants(storeRoot, object);
			if (!SmartFileUtils.isValidFile(object, size, normalizedHash)) throw new IOException("Recovery object is missing or corrupt: " + normalizedHash);
			SmartFileUtils.copyVerifiedAtomic(object, archivedObject, size, normalizedHash);
		}

		Jsons.ClientRecoveryArchiveFields archive = read(archiveRoot);
		boolean alreadyRecorded = archive.entries.stream().anyMatch(entry -> normalizedPath.equals(entry.logicalPath)
				&& normalizedHash.equalsIgnoreCase(entry.sha1) && size == entry.size);
		if (!alreadyRecorded) {
			Jsons.ClientRecoveryArchiveFields.EntryFields entry = new Jsons.ClientRecoveryArchiveFields.EntryFields();
			entry.logicalPath = normalizedPath;
			entry.sha1 = normalizedHash;
			entry.size = size;
			entry.sourceGenerationId = normalizedSourceGenerationId;
			entry.preservedAt = normalizedPreservedAt;
			List<Jsons.ClientRecoveryArchiveFields.EntryFields> entries = new ArrayList<>(archive.entries);
			entries.add(entry);
			entries.sort(ENTRY_ORDER);
			archive.entries = entries;
			ConfigTools.writeAtomic(archiveRoot.resolve("manifest.json"), archive);
		}
		return archivedObject;
	}

	public static Jsons.ClientRecoveryArchiveFields read(Path recoveryDirectory) throws IOException {
		Path archiveRoot = requireArchiveRoot(recoveryDirectory);
		Path manifestPath = archiveRoot.resolve("manifest.json");
		if (!Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
			Jsons.ClientRecoveryArchiveFields empty = new Jsons.ClientRecoveryArchiveFields();
			empty.entries = new ArrayList<>();
			return empty;
		}
		validateNoSymbolicLinkDescendants(archiveRoot, manifestPath);
		if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Recovery archive manifest is not a regular file");
		Jsons.ClientRecoveryArchiveFields archive;
		try {
			archive = ConfigTools.read(manifestPath, Jsons.ClientRecoveryArchiveFields.class)
					.orElseThrow(() -> new IOException("Recovery archive manifest is empty"));
		} catch (RuntimeException e) {
			throw new IOException("Recovery archive manifest is invalid", e);
		}
		if (archive.schemaVersion != 1 || archive.entries == null) throw new IOException("Recovery archive manifest identity is invalid");
		Path archiveObjects = requireDirectory(archiveRoot.resolve("objects"), "recovery object archive");
		Set<String> unique = new HashSet<>();
		List<Jsons.ClientRecoveryArchiveFields.EntryFields> sorted = new ArrayList<>(archive.entries);
		for (Jsons.ClientRecoveryArchiveFields.EntryFields entry : sorted) {
			if (entry == null || entry.logicalPath == null || !requirePath(entry.logicalPath).equals(entry.logicalPath))
				throw new IOException("Recovery archive entry path is invalid");
			String hash = requireHash(entry.sha1);
			requireOptionalGeneration(entry.sourceGenerationId);
			requireInstant(entry.preservedAt);
			if (entry.size < 0 || !unique.add(entry.logicalPath + "\0" + hash + "\0" + entry.size))
				throw new IOException("Recovery archive entry metadata is invalid");
			Path object = archiveObjects.resolve(hash).normalize();
			validateNoSymbolicLinkDescendants(archiveRoot, object);
			if (!SmartFileUtils.isValidFile(object, entry.size, hash)) throw new IOException("Archived recovery object is missing or corrupt: " + hash);
		}
		sorted.sort(ENTRY_ORDER);
		List<String> actualOrder = archive.entries.stream().map(entry -> entry.logicalPath + "\0" + entry.sha1.toLowerCase(Locale.ROOT) + "\0" + entry.size).toList();
		List<String> expectedOrder = sorted.stream().map(entry -> entry.logicalPath + "\0" + entry.sha1.toLowerCase(Locale.ROOT) + "\0" + entry.size).toList();
		if (!actualOrder.equals(expectedOrder)) throw new IOException("Recovery archive entries are not ordered");
		return archive;
	}

	private static Path requireDirectory(Path path, String description) throws IOException {
		Path normalized = Objects.requireNonNull(path, description).toAbsolutePath().normalize();
		if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) throw new IOException(description + " is not a directory: " + normalized);
		return normalized;
	}

	private static Path requireArchiveRoot(Path path) throws IOException {
		Path archiveRoot = Objects.requireNonNull(path, "recovery directory").toAbsolutePath().normalize();
		if (Files.isSymbolicLink(archiveRoot)) throw new IOException("Recovery archive root may not be a symbolic link");
		Files.createDirectories(archiveRoot);
		validateNoSymbolicLinkDescendants(archiveRoot, archiveRoot);
		return archiveRoot;
	}

	private static String requireOptionalGeneration(String value) throws IOException {
		if (value == null || value.isEmpty()) return "";
		if (!SHA1.matcher(value).matches() || !value.equals(value.toLowerCase(Locale.ROOT))) throw new IOException("Recovery source generation ID is invalid");
		return value;
	}

	private static String requireInstant(String value) throws IOException {
		if (value == null || value.isEmpty()) return "";
		try {
			Instant parsed = Instant.parse(value);
			if (!parsed.toString().equals(value)) throw new IOException("Recovery preservation timestamp is not canonical");
			return value;
		} catch (RuntimeException e) {
			throw new IOException("Recovery preservation timestamp is invalid", e);
		}
	}

	private static String requirePath(String path) throws IOException {
		try {
			return LogicalPath.requireCanonical(path);
		} catch (RuntimeException e) {
			throw new IOException("Recovery archive path is invalid", e);
		}
	}

	private static String requireHash(String sha1) throws IOException {
		if (sha1 == null || !SHA1.matcher(sha1).matches()) throw new IOException("Recovery object SHA-1 is invalid");
		return sha1.toLowerCase(Locale.ROOT);
	}

	private static void validateNoSymbolicLinkDescendants(Path constrainedRoot, Path target) throws IOException {
		Path root = constrainedRoot.toAbsolutePath().normalize();
		Path resolved = target.toAbsolutePath().normalize();
		if (!resolved.startsWith(root)) throw new IOException("Recovery archive path escapes its root");
		if (Files.isSymbolicLink(root)) throw new IOException("Recovery archive root may not be a symbolic link");
		Path current = root;
		for (Path component : root.relativize(resolved)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) throw new IOException("Recovery archive path contains a symbolic link: " + current);
		}
	}
}
