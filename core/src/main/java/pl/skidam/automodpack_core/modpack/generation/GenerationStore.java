package pl.skidam.automodpack_core.modpack.generation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.ServerObjectStore;
import pl.skidam.automodpack_core.utils.HashUtils;

public final class GenerationStore {
	public static final int CURRENT_POINTER_SCHEMA_VERSION = 1;

	public enum PublicationStatus {
		PUBLISHED, NO_CHANGES
	}

	public record CurrentSnapshot(GenerationRecord record, Path recordPath, NavigableMap<String, Path> hostingPaths) {
		public CurrentSnapshot {
			record = Objects.requireNonNull(record);
			recordPath = Objects.requireNonNull(recordPath).toAbsolutePath().normalize();
			hostingPaths = immutablePaths(hostingPaths);
		}
	}

	public record Publication(PublicationStatus status, GenerationRecord record, Path recordPath, NavigableMap<String, Path> hostingPaths) {
		public Publication {
			status = Objects.requireNonNull(status);
			record = Objects.requireNonNull(record);
			recordPath = Objects.requireNonNull(recordPath).toAbsolutePath().normalize();
			hostingPaths = immutablePaths(hostingPaths);
		}
	}

	@FunctionalInterface
	interface CommitHook {
		void beforeCurrentPointerReplacement() throws IOException;
	}

	private static final CommitHook NOOP_HOOK = () -> {};
	private final Path root;
	private final Path currentPath;
	private final Path recordsDirectory;
	private final Path objectsDirectory;
	private final Path stagingDirectory;
	private final ServerObjectStore objectStore;
	private final Clock clock;
	private final CommitHook commitHook;

	public GenerationStore(Path root) {
		this(root, Clock.systemUTC(), NOOP_HOOK);
	}

	GenerationStore(Path root, Clock clock, CommitHook commitHook) {
		this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
		this.currentPath = this.root.resolve(Constants.hostGenerationCurrentFile.getFileName());
		this.recordsDirectory = this.root.resolve(Constants.hostGenerationRecordsDir.getFileName());
		this.objectsDirectory = this.root.resolve(Constants.hostGenerationObjectsDir.getFileName());
		this.stagingDirectory = this.root.resolve(Constants.hostGenerationStagingDir.getFileName());
		this.clock = Objects.requireNonNull(clock);
		this.commitHook = Objects.requireNonNull(commitHook);
		this.objectStore = new ServerObjectStore(objectsDirectory, stagingDirectory);
	}

	public Path objectRoot() {
		return objectsDirectory;
	}

	public Optional<CurrentSnapshot> loadCurrent() throws IOException {
		if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) requireDirectory(root, "generation store");
		if (!Files.exists(currentPath, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		ensureRegular(currentPath, "current generation pointer");
		Jsons.GenerationPointerFields pointer;
		try {
			pointer = ConfigTools.parse(Files.readString(currentPath, StandardCharsets.UTF_8), Jsons.GenerationPointerFields.class);
		} catch (RuntimeException e) {
			throw new IOException("Invalid current generation pointer: " + currentPath, e);
		}
		if (pointer == null || pointer.schemaVersion != CURRENT_POINTER_SCHEMA_VERSION || !isDigest(pointer.generationId))
			throw new IOException("Invalid current generation pointer metadata: " + currentPath);
		requireDirectory(recordsDirectory, "generation records");
		Path recordPath = recordPath(pointer.generationId);
		GenerationRecord record = readRecord(recordPath);
		if (!record.metadata().generationId().equals(pointer.generationId))
			throw new IOException("Current pointer does not match generation record identity: " + recordPath);
		validateParentChain(record);
		NavigableMap<String, Path> hosting = verifyCurrentObjects(record);
		hosting.put("", recordPath);
		return Optional.of(new CurrentSnapshot(record, recordPath, hosting));
	}

	public Publication publish(ModpackCandidate candidate, Optional<CurrentSnapshot> expectedCurrent, String patchNotes) throws IOException {
		Objects.requireNonNull(candidate, "candidate");
		Objects.requireNonNull(expectedCurrent, "expectedCurrent");
		Optional<CurrentSnapshot> actualBefore = loadCurrent();
		ensureExpected(expectedCurrent, actualBefore);
		GenerationRecord previous = actualBefore.map(CurrentSnapshot::record).orElse(null);
		if (previous != null && !previous.manifest().modpackId().equals(candidate.manifest().modpackId()))
			throw new IOException("Modpack ID cannot change within a generation lineage");

		String stateDigest = GenerationIdentity.stateDigest(candidate.manifest());
		if (previous != null && previous.metadata().stateDigest().equals(stateDigest))
			return publication(PublicationStatus.NO_CHANGES, actualBefore.orElseThrow());

		ensureStoreDirectories();
		GenerationRecord record = GenerationRecord.create(candidate.manifest(), previous == null ? "" : previous.metadata().generationId(), clock.instant(), patchNotes);
		objectStore.promoteAll(candidate.objects());
		Path recordPath = recordPath(record.metadata().generationId());
		writeRecordNoClobber(recordPath, record);
		NavigableMap<String, Path> hosting = verifyCurrentObjects(record);
		hosting.put("", recordPath);
		Publication publication = new Publication(PublicationStatus.PUBLISHED, record, recordPath, hosting);
		Jsons.GenerationPointerFields nextPointer = pointer(record);
		commitHook.beforeCurrentPointerReplacement();
		ensureCurrentStillMatches(expectedCurrent);
		ConfigTools.writeAtomic(currentPath, nextPointer);
		return publication;
	}

	private Publication publication(PublicationStatus status, CurrentSnapshot snapshot) {
		return new Publication(status, snapshot.record(), snapshot.recordPath(), snapshot.hostingPaths());
	}

	private void ensureExpected(Optional<CurrentSnapshot> expected, Optional<CurrentSnapshot> actual) throws IOException {
		if (expected.isPresent() != actual.isPresent()) throw new IOException("Current generation changed before publication");
		if (expected.isPresent() && !expected.get().record().metadata().generationId().equals(actual.get().record().metadata().generationId()))
			throw new IOException("Current generation changed before publication");
	}

	private void ensureCurrentStillMatches(Optional<CurrentSnapshot> expected) throws IOException {
		ensureExpected(expected, loadCurrent());
	}

	private void ensureStoreDirectories() throws IOException {
		ensureDirectory(root, "generation store");
		ensureDirectory(recordsDirectory, "generation records");
		ensureDirectory(objectsDirectory, "immutable objects");
		ensureDirectory(stagingDirectory, "generation staging");
	}

	private GenerationRecord readRecord(Path path) throws IOException {
		ensureRegular(path, "generation record");
		try {
			return GenerationRecord.fromFields(ConfigTools.parse(Files.readString(path, StandardCharsets.UTF_8), Jsons.CompleteModpackContentFields.class));
		} catch (RuntimeException e) {
			throw new IOException("Invalid generation record: " + path, e);
		}
	}

	private void validateParentChain(GenerationRecord current) throws IOException {
		Set<String> visited = new HashSet<>();
		GenerationRecord record = current;
		while (true) {
			String id = record.metadata().generationId();
			if (!visited.add(id)) throw new IOException("Generation parent cycle detected at " + id);
			String parent = record.metadata().parentGenerationId();
			if (parent.isEmpty()) return;
			Path parentPath = recordPath(parent);
			record = readRecord(parentPath);
			if (!record.metadata().generationId().equals(parent)) throw new IOException("Generation parent filename does not match its identity: " + parentPath);
			if (!record.manifest().modpackId().equals(current.manifest().modpackId()))
				throw new IOException("Generation parent modpack ID does not match current lineage: " + parent);
		}
	}

	private NavigableMap<String, Path> verifyCurrentObjects(GenerationRecord record) throws IOException {
		requireDirectory(objectsDirectory, "immutable objects");
		TreeMap<String, Path> hosting = new TreeMap<>();
		Map<String, Long> expectedSizes = new HashMap<>();
		Set<String> verified = new HashSet<>();
		for (var group : record.manifest().groups().values()) for (var file : group.files().values()) {
			String sha1 = file.sha1().toLowerCase(Locale.ROOT);
			Path object = objectsDirectory.resolve(sha1).normalize();
			if (!object.startsWith(objectsDirectory)) throw new IOException("Object path escapes immutable object store: " + sha1);
			Long previousSize = expectedSizes.putIfAbsent(sha1, file.size());
			if (previousSize != null && previousSize.longValue() != file.size())
				throw new IOException("Immutable object has conflicting advertised sizes: " + sha1);
			if (verified.add(sha1)) {
				ensureRegular(object, "immutable object " + sha1);
				if (Files.size(object) != file.size() || !sha1.equals(HashUtils.getHash(object)))
					throw new IOException("Immutable object failed size/SHA-1 verification: " + object);
			}
			hosting.put(sha1, object);
		}
		return hosting;
	}

	private Path recordPath(String generationId) throws IOException {
		if (!isDigest(generationId)) throw new IOException("Invalid generation ID: " + generationId);
		return recordsDirectory.resolve(generationId + ".json");
	}

	private static Jsons.GenerationPointerFields pointer(GenerationRecord record) {
		Jsons.GenerationPointerFields pointer = new Jsons.GenerationPointerFields();
		pointer.schemaVersion = CURRENT_POINTER_SCHEMA_VERSION;
		pointer.generationId = record.metadata().generationId();
		return pointer;
	}

	private void writeRecordNoClobber(Path path, GenerationRecord record) throws IOException {
		ensureDirectory(recordsDirectory, "generation records");
		byte[] bytes = ConfigTools.GSON.toJson(record.toFields()).getBytes(StandardCharsets.UTF_8);
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			GenerationRecord existing = readRecord(path);
			if (!existing.equals(record)) throw new IOException("Generation record already exists with different content: " + path);
			return;
		}
		Path temporary = Files.createTempFile(recordsDirectory, ".record-", ".tmp");
		try {
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) channel.write(buffer);
				channel.force(true);
			}
			try {
				Files.createLink(path, temporary);
				forceDirectory(recordsDirectory);
			} catch (FileAlreadyExistsException e) {
				GenerationRecord existing = readRecord(path);
				if (!existing.equals(record)) throw new IOException("Generation record publication race: " + path, e);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static void ensureRegular(Path path, String description) throws IOException {
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Invalid " + description + ": expected a regular non-symlink file at " + path);
	}

	private static void ensureDirectory(Path path, String description) throws IOException {
		if (Files.isSymbolicLink(path)) throw new IOException("Managed " + description + " cannot be a symbolic link: " + path);
		Files.createDirectories(path);
		if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Invalid managed " + description + " directory: " + path);
	}

	private static void requireDirectory(Path path, String description) throws IOException {
		if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Invalid " + description + " directory: " + path);
	}

	private static void forceDirectory(Path directory) {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (IOException | UnsupportedOperationException ignored) {
			// Directory fsync is unavailable on some supported filesystems.
		}
	}

	private static boolean isDigest(String value) {
		return value != null && value.matches("[0-9a-f]{40}");
	}

	private static NavigableMap<String, Path> immutablePaths(Map<String, Path> paths) {
		TreeMap<String, Path> sorted = new TreeMap<>();
		if (paths != null) for (var entry : paths.entrySet()) sorted.put(entry.getKey(), entry.getValue().toAbsolutePath().normalize());
		return Collections.unmodifiableNavigableMap(sorted);
	}
}
