package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

/** Global, user-controlled storage for ordinary jars displaced from the client mods directory. */
public final class LocalModArchive {
	private static final Object MUTATION_LOCK = new Object();
	private static final Comparator<ClientStorageJsons.ClientLocalModArchiveFields.EntryFields> ENTRY_ORDER = Comparator.comparing(entry -> entry.entryId);
	private static final String ARCHIVE_OPERATION = "ARCHIVE";
	private static final String RESTORE_OPERATION = "RESTORE";

	private LocalModArchive() {}

	public record ArchiveEntry(String entryId, String originalPath, String sha1, long size, String archivedAt) {
		public ArchiveEntry {
			if (entryId == null || !entryId.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Local mod archive entry ID is invalid");
			originalPath = canonicalPath(originalPath);
			if (sha1 == null || !sha1.matches("[0-9a-fA-F]{40}")) throw new IllegalArgumentException("Local mod archive SHA-1 is invalid");
			sha1 = sha1.toLowerCase(Locale.ROOT);
			if (size < 0) throw new IllegalArgumentException("Local mod archive size is invalid");
			archivedAt = archivedAt == null ? "" : archivedAt;
		}
	}

	public record Snapshot(List<ArchiveEntry> entries) {
		public Snapshot {
			entries = List.copyOf(entries);
		}
	}

	/** Lists direct regular jars in mods, excluding the currently loaded AutoModpack jar. */
	public static Snapshot candidates(ClientStorage storage, Path excludedJar, FileMetadataCache cache) throws IOException {
		Path mods = storage.modsDirectory().toAbsolutePath().normalize();
		if (Files.notExists(mods, LinkOption.NOFOLLOW_LINKS)) return new Snapshot(List.of());
		if (Files.isSymbolicLink(mods) || !Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client mods directory is not a real directory: " + mods);
		Path excluded = excludedJar == null ? null : excludedJar.toAbsolutePath().normalize();
		try (Stream<Path> paths = Files.list(mods)) {
			List<ArchiveEntry> entries = new ArrayList<>();
			for (Path path : paths.sorted().toList()) {
				if (excluded != null && path.toAbsolutePath().normalize().equals(excluded)) continue;
				if (!isDirectJar(storage, path)) continue;
				long size = Files.size(path);
				String sha1 = cache == null ? HashUtils.getHash(path) : cache.getOrComputeHash(path);
				if (sha1 == null) throw new IOException("Could not verify local mod: " + path);
				String originalPath = relativePath(storage, path);
				entries.add(new ArchiveEntry(entryId(originalPath, sha1, size), originalPath, sha1, size, ""));
			}
			return new Snapshot(entries);
		}
	}

	/** Reads and verifies the global archive manifest and every referenced payload. */
	public static Snapshot snapshot(ClientStorage storage) throws IOException {
		synchronized (MUTATION_LOCK) {
			recoverPending(storage);
			return toSnapshot(readManifest(storage, true));
		}
	}

	public static boolean hasEntries(ClientStorage storage) throws IOException {
		synchronized (MUTATION_LOCK) {
			recoverPending(storage);
			return !readManifest(storage, false).entries.isEmpty();
		}
	}

	/** Moves only the supplied candidate entries. This operation never discovers or changes other jars. */
	public static void archive(ClientStorage storage, List<ArchiveEntry> selected, FileMetadataCache cache) throws IOException {
		if (selected == null) throw new IllegalArgumentException("Selected local mods are missing");
		synchronized (MUTATION_LOCK) {
			storage.ensureRoots();
			recoverPending(storage);
			ClientStorageJsons.ClientLocalModArchiveFields manifest = readManifest(storage, true);
			Set<String> selectedIds = new HashSet<>();
			for (ArchiveEntry candidate : selected) {
				if (candidate == null || !selectedIds.add(candidate.entryId())) throw new IOException("Local mod selection contains a duplicate entry");
				archiveOne(storage, manifest, candidate, cache);
			}
		}
	}

	/** Restores one entry only when the destination is absent or already byte-identical. */
	public static void restore(ClientStorage storage, String entryId) throws IOException {
		synchronized (MUTATION_LOCK) {
			storage.ensureRoots();
			recoverPending(storage);
			ClientStorageJsons.ClientLocalModArchiveFields manifest = readManifest(storage, true);
			ClientStorageJsons.ClientLocalModArchiveFields.EntryFields entry = manifest.entries.stream().filter(value -> entryId != null && entryId.equals(value.entryId)).findFirst()
					.orElseThrow(() -> new IOException("Local mod archive entry is no longer available: " + entryId));
			restoreOne(storage, manifest, entry);
		}
	}

	private static void archiveOne(ClientStorage storage, ClientStorageJsons.ClientLocalModArchiveFields manifest, ArchiveEntry candidate, FileMetadataCache cache) throws IOException {
		ClientStorageJsons.ClientLocalModArchiveFields.EntryFields existing = manifest.entries.stream().filter(value -> candidate.entryId().equals(value.entryId)).findFirst().orElse(null);
		Path source = sourcePath(storage, candidate.originalPath());
		Path payload = storage.localModArchivePayload(candidate.entryId());
		validateArchivePath(storage, payload);
		if (existing != null) {
			validateEntry(storage, existing, true);
			if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Local mod is already archived: " + source);
			return;
		}
		if (!isDirectJar(storage, source) || !matches(source, candidate.size(), candidate.sha1(), cache)) throw new IOException("Local mod changed before archiving: " + source);
		ClientStorageJsons.ClientLocalModArchiveFields.EntryFields archived = toFields(storage, candidate, Instant.now().toString());
		writePending(storage, ARCHIVE_OPERATION, archived);
		moveIntoArchive(source, payload, candidate.size(), candidate.sha1());
		manifest.entries = appendSorted(manifest.entries, archived);
		writeManifest(storage, manifest);
		Files.deleteIfExists(storage.localModArchivePendingFile());
	}

	private static void restoreOne(ClientStorage storage, ClientStorageJsons.ClientLocalModArchiveFields manifest,
			ClientStorageJsons.ClientLocalModArchiveFields.EntryFields entry) throws IOException {
		validateEntry(storage, entry, true);
		Path destination = sourcePath(storage, entry.originalPath);
		validateGamePath(storage, destination);
		writePending(storage, RESTORE_OPERATION, entry);
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			if (Files.isSymbolicLink(destination) || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
					|| !SmartFileUtils.isValidFile(destination, entry.size, entry.sha1)) {
				Files.deleteIfExists(storage.localModArchivePendingFile());
				throw new IOException("Restore destination contains different bytes: " + destination);
			}
			removeEntryAndPayload(storage, manifest, entry);
			Files.deleteIfExists(storage.localModArchivePendingFile());
			return;
		}
		Path payload = storage.localModArchivePayload(entry.entryId);
		moveOutOfArchive(storage, payload, destination, entry.size, entry.sha1);
		removeEntryAndPayload(storage, manifest, entry);
		Files.deleteIfExists(storage.localModArchivePendingFile());
	}

	private static void recoverPending(ClientStorage storage) throws IOException {
		Path pendingPath = storage.localModArchivePendingFile();
		if (Files.notExists(pendingPath, LinkOption.NOFOLLOW_LINKS)) return;
		if (Files.isSymbolicLink(pendingPath) || !Files.isRegularFile(pendingPath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Local mod archive journal is invalid");
		ClientStorageJsons.ClientLocalModArchiveFields.PendingFields pending;
		try {
			pending = ConfigTools.read(pendingPath, ClientStorageJsons.ClientLocalModArchiveFields.PendingFields.class)
					.orElseThrow(() -> new IOException("Local mod archive journal is empty"));
		} catch (RuntimeException e) {
			throw new IOException("Local mod archive journal is invalid", e);
		}
		if (pending.schemaVersion != 1 || pending.entry == null || (!ARCHIVE_OPERATION.equals(pending.operation) && !RESTORE_OPERATION.equals(pending.operation)))
			throw new IOException("Local mod archive journal identity is invalid");
		ClientStorageJsons.ClientLocalModArchiveFields manifest = readManifest(storage, false);
		if (ARCHIVE_OPERATION.equals(pending.operation)) recoverArchive(storage, manifest, pending.entry);
		else recoverRestore(storage, manifest, pending.entry);
	}

	private static void recoverArchive(ClientStorage storage, ClientStorageJsons.ClientLocalModArchiveFields manifest,
			ClientStorageJsons.ClientLocalModArchiveFields.EntryFields entry) throws IOException {
		validateEntryMetadata(storage, entry);
		Path source = sourcePath(storage, entry.originalPath);
		Path payload = storage.localModArchivePayload(entry.entryId);
		ClientStorageJsons.ClientLocalModArchiveFields.EntryFields existing = manifest.entries.stream().filter(value -> entry.entryId.equals(value.entryId)).findFirst().orElse(null);
		boolean sourceValid = isDirectJar(storage, source) && SmartFileUtils.isValidFile(source, entry.size, entry.sha1);
		boolean payloadValid = SmartFileUtils.isValidFile(payload, entry.size, entry.sha1);
		if (existing != null) {
			if (!payloadValid) throw new IOException("Committed local mod archive payload is missing or corrupt: " + payload);
			if (sourceValid) Files.delete(source);
		} else if (payloadValid && !sourceValid) {
			manifest.entries = appendSorted(manifest.entries, entry);
			writeManifest(storage, manifest);
		} else if (sourceValid && payloadValid) {
			Files.delete(payload);
		} else if (!sourceValid && !payloadValid) {
			throw new IOException("Interrupted local mod archive move lost both source and payload: " + entry.originalPath);
		}
		Files.deleteIfExists(storage.localModArchivePendingFile());
	}

	private static void recoverRestore(ClientStorage storage, ClientStorageJsons.ClientLocalModArchiveFields manifest,
			ClientStorageJsons.ClientLocalModArchiveFields.EntryFields entry) throws IOException {
		validateEntryMetadata(storage, entry);
		ClientStorageJsons.ClientLocalModArchiveFields.EntryFields existing = manifest.entries.stream().filter(value -> entry.entryId.equals(value.entryId)).findFirst().orElse(null);
		if (existing == null) {
			Path payload = storage.localModArchivePayload(entry.entryId);
			if (Files.exists(payload, LinkOption.NOFOLLOW_LINKS)) {
				if (!SmartFileUtils.isValidFile(payload, entry.size, entry.sha1)) throw new IOException("Local mod archive payload changed: " + payload);
				Files.delete(payload);
			}
			Files.deleteIfExists(storage.localModArchivePendingFile());
			return;
		}
		Path destination = sourcePath(storage, entry.originalPath);
		validateGamePath(storage, destination);
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			if (Files.isSymbolicLink(destination) || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
					|| !SmartFileUtils.isValidFile(destination, entry.size, entry.sha1)) {
				Files.deleteIfExists(storage.localModArchivePendingFile());
				throw new IOException("Restore destination contains different bytes: " + destination);
			}
			removeEntryAndPayload(storage, manifest, entry);
		} else {
			Path payload = storage.localModArchivePayload(entry.entryId);
			moveOutOfArchive(storage, payload, destination, entry.size, entry.sha1);
			removeEntryAndPayload(storage, manifest, entry);
		}
		Files.deleteIfExists(storage.localModArchivePendingFile());
	}

	private static void removeEntryAndPayload(ClientStorage storage, ClientStorageJsons.ClientLocalModArchiveFields manifest,
			ClientStorageJsons.ClientLocalModArchiveFields.EntryFields entry) throws IOException {
		Path payload = storage.localModArchivePayload(entry.entryId);
		if (Files.exists(payload, LinkOption.NOFOLLOW_LINKS) && !SmartFileUtils.isValidFile(payload, entry.size, entry.sha1))
			throw new IOException("Local mod archive payload changed: " + payload);
		manifest.entries = manifest.entries.stream().filter(value -> !entry.entryId.equals(value.entryId)).toList();
		writeManifest(storage, manifest);
		if (Files.exists(payload, LinkOption.NOFOLLOW_LINKS)) {
			Files.delete(payload);
		}
	}

	private static void moveIntoArchive(Path source, Path payload, long size, String sha1) throws IOException {
		try {
			Files.move(source, payload, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			SmartFileUtils.copyVerifiedAtomic(source, payload, size, sha1);
			Files.delete(source);
		}
		if (!SmartFileUtils.isValidFile(payload, size, sha1)) throw new IOException("Archived local mod failed verification: " + payload);
	}

	private static void moveOutOfArchive(ClientStorage storage, Path payload, Path destination, long size, String sha1) throws IOException {
		validateArchivePath(storage, payload);
		validateGamePath(storage, destination);
		Path parent = destination.getParent();
		if (parent == null) throw new IOException("Restore destination has no parent: " + destination);
		Files.createDirectories(parent);
		try {
			Files.move(payload, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			Path temporary = Files.createTempFile(parent, "." + destination.getFileName() + ".", ".restore.tmp");
			try {
				Files.copy(payload, temporary, StandardCopyOption.REPLACE_EXISTING);
				if (!SmartFileUtils.isValidFile(temporary, size, sha1)) throw new IOException("Restored local mod failed verification: " + temporary);
				try {
					Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException noAtomicMove) {
					Files.move(temporary, destination);
				}
			} finally {
				Files.deleteIfExists(temporary);
			}
		}
		if (!SmartFileUtils.isValidFile(destination, size, sha1)) throw new IOException("Restored local mod failed verification: " + destination);
	}

	private static ClientStorageJsons.ClientLocalModArchiveFields readManifest(ClientStorage storage, boolean verifyPayload) throws IOException {
		Path root = storage.localModArchiveDirectory();
		if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
			ClientStorageJsons.ClientLocalModArchiveFields empty = new ClientStorageJsons.ClientLocalModArchiveFields();
			empty.entries = new ArrayList<>();
			return empty;
		}
		validateArchivePath(storage, root);
		Path manifestPath = storage.localModArchiveManifest();
		if (Files.notExists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
			ClientStorageJsons.ClientLocalModArchiveFields empty = new ClientStorageJsons.ClientLocalModArchiveFields();
			empty.entries = new ArrayList<>();
			return empty;
		}
		if (Files.isSymbolicLink(manifestPath) || !Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Local mod archive manifest is invalid");
		ClientStorageJsons.ClientLocalModArchiveFields manifest;
		try {
			manifest = ConfigTools.read(manifestPath, ClientStorageJsons.ClientLocalModArchiveFields.class)
					.orElseThrow(() -> new IOException("Local mod archive manifest is empty"));
		} catch (RuntimeException e) {
			throw new IOException("Local mod archive manifest is invalid", e);
		}
		if (manifest.schemaVersion != 1 || manifest.entries == null) throw new IOException("Local mod archive manifest identity is invalid");
		Set<String> ids = new HashSet<>();
		for (ClientStorageJsons.ClientLocalModArchiveFields.EntryFields entry : manifest.entries) {
			if (entry == null || !ids.add(entry.entryId)) throw new IOException("Local mod archive contains duplicate or incomplete entries");
			validateEntryMetadata(storage, entry);
			if (verifyPayload) validateEntry(storage, entry, true);
		}
		List<ClientStorageJsons.ClientLocalModArchiveFields.EntryFields> sorted = new ArrayList<>(manifest.entries);
		sorted.sort(ENTRY_ORDER);
		if (!sorted.equals(manifest.entries)) throw new IOException("Local mod archive entries are not ordered");
		return manifest;
	}

	private static void validateEntry(ClientStorage storage, ClientStorageJsons.ClientLocalModArchiveFields.EntryFields entry, boolean verifyPayload) throws IOException {
		validateEntryMetadata(storage, entry);
		if (verifyPayload && !SmartFileUtils.isValidFile(storage.localModArchivePayload(entry.entryId), entry.size, entry.sha1))
			throw new IOException("Local mod archive payload is missing or corrupt: " + entry.entryId);
	}

	private static void validateEntryMetadata(ClientStorage storage, ClientStorageJsons.ClientLocalModArchiveFields.EntryFields entry) throws IOException {
		if (entry.entryId == null || !entry.entryId.matches("[0-9a-f]{40}") || entry.sha1 == null || !entry.sha1.matches("[0-9a-fA-F]{40}") || entry.size < 0)
			throw new IOException("Local mod archive entry metadata is invalid");
		String originalPath = canonicalPath(entry.originalPath);
		if (!entry.originalPath.equals(originalPath)) throw new IOException("Local mod archive source path is invalid");
		String expectedArchivePath = archivePath(storage, entry.entryId);
		if (!expectedArchivePath.equals(entry.archivePath)) throw new IOException("Local mod archive payload path is invalid");
		if (entry.archivedAt == null || entry.archivedAt.isBlank()) throw new IOException("Local mod archive timestamp is invalid");
		try {
			if (!Instant.parse(entry.archivedAt).toString().equals(entry.archivedAt)) throw new IOException("Local mod archive timestamp is invalid");
		} catch (RuntimeException e) {
			throw new IOException("Local mod archive timestamp is invalid", e);
		}
		validateArchivePath(storage, storage.localModArchivePayload(entry.entryId));
	}

	private static ClientStorageJsons.ClientLocalModArchiveFields.EntryFields toFields(ClientStorage storage, ArchiveEntry candidate, String archivedAt) {
		ClientStorageJsons.ClientLocalModArchiveFields.EntryFields entry = new ClientStorageJsons.ClientLocalModArchiveFields.EntryFields();
		entry.entryId = candidate.entryId();
		entry.originalPath = candidate.originalPath();
		entry.archivePath = archivePath(storage, candidate.entryId());
		entry.sha1 = candidate.sha1();
		entry.size = candidate.size();
		entry.archivedAt = archivedAt;
		return entry;
	}

	private static void writeManifest(ClientStorage storage, ClientStorageJsons.ClientLocalModArchiveFields manifest) throws IOException {
		manifest.schemaVersion = 1;
		manifest.entries = manifest.entries.stream().sorted(ENTRY_ORDER).toList();
		Files.createDirectories(storage.localModArchiveDirectory().resolve("payload"));
		ConfigTools.writeAtomic(storage.localModArchiveManifest(), manifest);
	}

	private static void writePending(ClientStorage storage, String operation, ClientStorageJsons.ClientLocalModArchiveFields.EntryFields entry) throws IOException {
		ClientStorageJsons.ClientLocalModArchiveFields.PendingFields pending = new ClientStorageJsons.ClientLocalModArchiveFields.PendingFields();
		pending.operation = operation;
		pending.entry = entry;
		Files.createDirectories(storage.localModArchiveDirectory());
		ConfigTools.writeAtomic(storage.localModArchivePendingFile(), pending);
	}

	private static List<ClientStorageJsons.ClientLocalModArchiveFields.EntryFields> appendSorted(List<ClientStorageJsons.ClientLocalModArchiveFields.EntryFields> entries,
			ClientStorageJsons.ClientLocalModArchiveFields.EntryFields entry) {
		List<ClientStorageJsons.ClientLocalModArchiveFields.EntryFields> result = new ArrayList<>(entries);
		result.add(entry);
		result.sort(ENTRY_ORDER);
		return result;
	}

	private static Snapshot toSnapshot(ClientStorageJsons.ClientLocalModArchiveFields manifest) {
		return new Snapshot(manifest.entries.stream().map(entry -> new ArchiveEntry(entry.entryId, entry.originalPath, entry.sha1, entry.size, entry.archivedAt)).toList());
	}

	private static boolean matches(Path path, long size, String sha1, FileMetadataCache cache) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != size) return false;
		String actual = cache == null ? HashUtils.getHash(path) : cache.getOrComputeHash(path);
		return actual != null && actual.equalsIgnoreCase(sha1);
	}

	private static boolean isDirectJar(ClientStorage storage, Path path) {
		Path normalized = path.toAbsolutePath().normalize();
		return normalized.getParent() != null && normalized.getParent().equals(storage.modsDirectory().toAbsolutePath().normalize())
				&& normalized.getFileName() != null && normalized.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
				&& Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS);
	}

	private static Path sourcePath(ClientStorage storage, String originalPath) throws IOException {
		String canonical = canonicalPath(originalPath);
		if (!canonical.startsWith("mods/")) throw new IOException("Local mod archive path is outside mods");
		Path path = storage.gamePath(canonical);
		validateGamePath(storage, path);
		return path;
	}

	private static String relativePath(ClientStorage storage, Path path) {
		return UpdatePlanner.normalize(storage.gameDirectory().toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString());
	}

	private static String archivePath(ClientStorage storage, String entryId) {
		return UpdatePlanner.normalize(storage.localModArchiveDirectory().relativize(storage.localModArchivePayload(entryId)).toString());
	}

	private static String canonicalPath(String value) {
		if (value == null || value.indexOf('\\') >= 0) throw new IllegalArgumentException("Local mod archive path is invalid");
		String normalized = UpdatePlanner.normalize(value);
		if (!normalized.startsWith("mods/") || normalized.length() <= 5 || normalized.substring(5).contains("/")) throw new IllegalArgumentException("Local mod archive path is not a direct mod jar");
		if (!normalized.toLowerCase(Locale.ROOT).endsWith(".jar")) throw new IllegalArgumentException("Local mod archive path is not a jar");
		return normalized;
	}

	private static void validateArchivePath(ClientStorage storage, Path path) throws IOException {
		validateNoSymlinks(storage.localModArchiveDirectory(), path);
	}

	private static void validateGamePath(ClientStorage storage, Path path) throws IOException {
		validateNoSymlinks(storage.gameDirectory(), path);
	}

	private static void validateNoSymlinks(Path root, Path path) throws IOException {
		Path normalizedRoot = root.toAbsolutePath().normalize();
		Path normalizedPath = path.toAbsolutePath().normalize();
		if (!normalizedPath.startsWith(normalizedRoot)) throw new IOException("Path escaped its managed root: " + path);
		if (Files.isSymbolicLink(normalizedRoot)) throw new IOException("Managed root is a symbolic link: " + normalizedRoot);
		Path current = normalizedRoot;
		for (Path component : normalizedRoot.relativize(normalizedPath)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) throw new IOException("Managed path contains a symbolic link: " + current);
		}
	}

	private static String entryId(String originalPath, String sha1, long size) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			String value = originalPath + "\n" + sha1.toLowerCase(Locale.ROOT) + "\n" + size;
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-1 is required by the local mod archive layout", e);
		}
	}
}
