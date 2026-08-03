package pl.skidam.automodpack_core.modpack.generation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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

	/** A deterministic receipt for the regular files in the generation store. */
	public record StorageReport(long recordCount, long recordBytes, long catalogueCount, long catalogueBytes, long commitCount, long commitBytes,
			long deltaCount, long deltaBytes, long immutableObjectCount, long immutableObjectBytes, long stagingFileCount, long stagingBytes,
			long referencedObjectCount, long referencedObjectBytes, long objectReferenceCount) {
		public StorageReport {
			if (recordCount < 0 || recordBytes < 0 || catalogueCount < 0 || catalogueBytes < 0 || commitCount < 0 || commitBytes < 0 || deltaCount < 0
					|| deltaBytes < 0 || immutableObjectCount < 0 || immutableObjectBytes < 0 || stagingFileCount < 0 || stagingBytes < 0 || referencedObjectCount < 0
					|| referencedObjectBytes < 0 || objectReferenceCount < 0)
				throw new IllegalArgumentException("Storage report values cannot be negative");
		}

		/** The ratio of unique referenced object hashes to all logical record references. */
		public OptionalDouble uniqueObjectReferenceRatio() {
			return objectReferenceCount == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) referencedObjectCount / objectReferenceCount);
		}

		/** The ratio of unique referenced object hashes to measured immutable object files. */
		public OptionalDouble referencedObjectRatio() {
			return immutableObjectCount == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) referencedObjectCount / immutableObjectCount);
		}
	}

	/** The result of one explicitly requested object collection pass. */
	public record CollectionResult(long beforeObjectBytes, long afterObjectBytes, long beforeObjectCount, long afterObjectCount,
			long deletedObjectCount, long deletedObjectBytes) {
		public CollectionResult {
			if (beforeObjectBytes < 0 || afterObjectBytes < 0 || beforeObjectCount < 0 || afterObjectCount < 0 || deletedObjectCount < 0 || deletedObjectBytes < 0)
				throw new IllegalArgumentException("Collection result values cannot be negative");
		}
	}

	@FunctionalInterface
	interface CommitHook {
		void beforeCurrentPointerReplacement() throws IOException;
	}

	@FunctionalInterface
	private interface ImmutableJsonReader<T> {
		T read(Path path) throws IOException;
	}

	private static final CommitHook NOOP_HOOK = () -> {};
	private static final Map<Path, ReentrantLock> PUBLICATION_LOCKS = new ConcurrentHashMap<>();
	private final Path root;
	private final Path currentPath;
	private final Path currentProjectionPath;
	private final Path publicationLockPath;
	private final Path recordsDirectory;
	private final Path cataloguesDirectory;
	private final Path commitsDirectory;
	private final Path deltasDirectory;
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
		this.currentProjectionPath = this.root.resolve(Constants.hostGenerationCurrentProjectionFile.getFileName());
		this.publicationLockPath = this.root.resolve(".publication.lock");
		this.recordsDirectory = this.root.resolve(Constants.hostGenerationRecordsDir.getFileName());
		this.cataloguesDirectory = this.root.resolve(Constants.hostGenerationCataloguesDir.getFileName());
		this.commitsDirectory = this.root.resolve(Constants.hostGenerationCommitsDir.getFileName());
		this.deltasDirectory = this.root.resolve(Constants.hostGenerationDeltasDir.getFileName());
		this.objectsDirectory = this.root.resolve(Constants.hostGenerationObjectsDir.getFileName());
		this.stagingDirectory = this.root.resolve(Constants.hostGenerationStagingDir.getFileName());
		this.clock = Objects.requireNonNull(clock);
		this.commitHook = Objects.requireNonNull(commitHook);
		this.objectStore = new ServerObjectStore(objectsDirectory, stagingDirectory);
	}

	public Path objectRoot() {
		return objectsDirectory;
	}

	/** Measures the current generation store without publishing or deleting managed state. */
	public StorageReport measureStorage() throws IOException {
		ensureDirectory(root, "generation store");
		try (PublicationGuard ignored = acquirePublicationGuard()) {
			return measureStorageLocked();
		}
	}

	/** Collects only explicitly unreferenced immutable objects; this method is never called automatically. */
	public CollectionResult collectUnreachableObjects(Set<String> retainedGenerationIds, Set<String> pinnedObjectHashes) throws IOException {
		Objects.requireNonNull(retainedGenerationIds, "retainedGenerationIds");
		Objects.requireNonNull(pinnedObjectHashes, "pinnedObjectHashes");
		NavigableSet<String> generationPins = canonicalPins(retainedGenerationIds, "generation");
		NavigableSet<String> objectPins = canonicalPins(pinnedObjectHashes, "object");
		ensureDirectory(root, "generation store");
		try (PublicationGuard ignored = acquirePublicationGuard()) {
			return collectUnreachableObjectsLocked(generationPins, objectPins);
		}
	}

	/** Short alias for callers that treat collection as the store's explicit maintenance operation. */
	public CollectionResult collect(Set<String> retainedGenerationIds, Set<String> pinnedObjectHashes) throws IOException {
		return collectUnreachableObjects(retainedGenerationIds, pinnedObjectHashes);
	}

	/** Loads the current materialized projection and verifies only the active target objects. */
	public Optional<CurrentSnapshot> loadCurrent() throws IOException {
		return loadCurrent(false, false);
	}

	/** Performs an explicit ancestry and historical-object verification pass. */
	public Optional<CurrentSnapshot> loadCurrentDeep() throws IOException {
		return loadCurrent(true, false);
	}

	/** Repairs a missing or invalid projection under the publication lock before returning the active hosting map. */
	public Optional<CurrentSnapshot> loadCurrentAndRepair() throws IOException {
		ensureDirectory(root, "generation store");
		try (PublicationGuard ignored = acquirePublicationGuard()) {
			return loadCurrent(false, true);
		}
	}

	private Optional<CurrentSnapshot> loadCurrent(boolean deepVerification, boolean repairProjection) throws IOException {
		if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) requireDirectory(root, "generation store");
		if (!Files.exists(currentPath, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		Jsons.GenerationPointerFields pointer = readCurrentPointer();
		requireDirectory(recordsDirectory, "generation records");
		Path recordPath = recordPath(pointer.generationId);
		ensureRegular(recordPath, "current generation record");
		GenerationRecord record;
		Path materializedPath = recordPath;
		LoadedRecord loaded = null;
		if (deepVerification) {
			record = readRecord(recordPath);
		} else {
			loaded = readProjectionOrRecord(recordPath);
			record = loaded.record();
			materializedPath = loaded.path();
		}
		if (!record.metadata().generationId().equals(pointer.generationId))
			throw new IOException("Current pointer does not match current generation identity: " + recordPath);
		if (deepVerification) {
			validateParentChain(record);
			verifyAllReferencedObjects(record);
		}
		NavigableMap<String, Path> hosting = deepVerification ? activeTargetPaths(record) : verifyActiveTargetObjects(record);
		if (repairProjection && loaded != null && loaded.needsRepair()) {
			writeCurrentProjection(record);
			materializedPath = currentProjectionPath;
		}
		hosting.put("", materializedPath);
		return Optional.of(new CurrentSnapshot(record, recordPath, hosting));
	}

	public Publication publish(ModpackCandidate candidate, Optional<CurrentSnapshot> expectedCurrent, String patchNotes) throws IOException {
		ensureDirectory(root, "generation store");
		try (PublicationGuard ignored = acquirePublicationGuard()) {
			return publishLocked(candidate, expectedCurrent, patchNotes);
		}
	}

	public Publication publishRevert(String targetGenerationId, Optional<CurrentSnapshot> expectedCurrent, String patchNotes) throws IOException {
		if (!isDigest(targetGenerationId)) throw new IOException("Invalid rollback target generation ID: " + targetGenerationId);
		Objects.requireNonNull(expectedCurrent, "expectedCurrent");
		ensureDirectory(root, "generation store");
		try (PublicationGuard ignored = acquirePublicationGuard()) {
			Optional<CurrentSnapshot> actualBefore = loadCurrent();
			ensureExpected(expectedCurrent, actualBefore);
			GenerationRecord previous = actualBefore.map(CurrentSnapshot::record).orElseThrow(() -> new IOException("Cannot revert before the root generation is published"));
			GenerationRecord target = findAncestor(previous, targetGenerationId);
			if (target == null) throw new IOException("Rollback target is not in the current generation history: " + targetGenerationId);
			ensureStoreDirectories();
			GenerationRecord record = GenerationRecord.create(target.manifest(), previous, clock.instant(), patchNotes, targetGenerationId);
			Path recordPath = recordPath(record.metadata().generationId());
			writeRecordNoClobber(recordPath, record);
			OwnershipDelta delta = writeDeltaNoClobber(record, previous);
			writeCatalogueNoClobber(record);
			writeCommitNoClobber(record, delta);
			NavigableMap<String, Path> hosting = verifyActiveTargetObjects(record);
			writeCurrentProjection(record);
			hosting.put("", currentProjectionPath);
			commitHook.beforeCurrentPointerReplacement();
			ensureCurrentStillMatches(expectedCurrent);
			ConfigTools.writeAtomic(currentPath, pointer(record));
			return new Publication(PublicationStatus.PUBLISHED, record, recordPath, hosting);
		}
	}

	public List<GenerationRecord> currentHistory() throws IOException {
		Optional<CurrentSnapshot> current = loadCurrentDeep();
		if (current.isEmpty()) return List.of();
		List<GenerationRecord> reverse = new ArrayList<>();
		GenerationRecord record = current.orElseThrow().record();
		while (true) {
			reverse.add(record);
			String parent = record.metadata().parentGenerationId();
			if (parent.isEmpty()) break;
			record = readRecord(recordPath(parent));
		}
		Collections.reverse(reverse);
		return List.copyOf(reverse);
	}

	private GenerationRecord findAncestor(GenerationRecord current, String targetGenerationId) throws IOException {
		GenerationRecord record = current;
		while (true) {
			if (record.metadata().generationId().equals(targetGenerationId)) return record;
			String parent = record.metadata().parentGenerationId();
			if (parent.isEmpty()) return null;
			record = readRecord(recordPath(parent));
		}
	}

	private Publication publishLocked(ModpackCandidate candidate, Optional<CurrentSnapshot> expectedCurrent, String patchNotes) throws IOException {
		Objects.requireNonNull(candidate, "candidate");
		Objects.requireNonNull(expectedCurrent, "expectedCurrent");
		Optional<CurrentSnapshot> actualBefore = loadCurrent();
		ensureExpected(expectedCurrent, actualBefore);
		GenerationRecord previous = actualBefore.map(CurrentSnapshot::record).orElse(null);
		if (previous != null && !previous.manifest().modpackId().equals(candidate.manifest().modpackId()))
			throw new IOException("Modpack ID cannot change within a generation lineage");

		String stateDigest = GenerationIdentity.stateDigest(candidate.manifest());
		if (previous != null && previous.metadata().stateDigest().equals(stateDigest)) {
			OwnershipLedger candidateLedger;
			try {
				candidateLedger = OwnershipLedger.materializeWithoutGeneration(previous.ownershipLedger(), candidate.manifest());
			} catch (RuntimeException e) {
				throw new IOException("Candidate ownership ledger is invalid", e);
			}
			if (previous.metadata().ledgerDigest().equals(candidateLedger.digest())) return publication(PublicationStatus.NO_CHANGES, actualBefore.orElseThrow());
		}

		ensureStoreDirectories();
		GenerationRecord record = GenerationRecord.create(candidate.manifest(), previous, clock.instant(), patchNotes);
		objectStore.promoteAll(candidate.objects());
		Path recordPath = recordPath(record.metadata().generationId());
		writeRecordNoClobber(recordPath, record);
		OwnershipDelta delta = writeDeltaNoClobber(record, previous);
		writeCatalogueNoClobber(record);
		writeCommitNoClobber(record, delta);
		NavigableMap<String, Path> hosting = verifyActiveTargetObjects(record);
		writeCurrentProjection(record);
		hosting.put("", currentProjectionPath);
		Publication publication = new Publication(PublicationStatus.PUBLISHED, record, recordPath, hosting);
		Jsons.GenerationPointerFields nextPointer = pointer(record);
		commitHook.beforeCurrentPointerReplacement();
		ensureCurrentStillMatches(expectedCurrent);
		ConfigTools.writeAtomic(currentPath, nextPointer);
		return publication;
	}

	private PublicationGuard acquirePublicationGuard() throws IOException {
		ReentrantLock jvmLock = PUBLICATION_LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock());
		jvmLock.lock();
		FileChannel channel = null;
		try {
			channel = FileChannel.open(publicationLockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
			FileLock fileLock = channel.lock();
			return new PublicationGuard(jvmLock, channel, fileLock);
		} catch (IOException | RuntimeException e) {
			if (channel != null) try {
				channel.close();
			} catch (IOException closeFailure) {
				e.addSuppressed(closeFailure);
			}
			jvmLock.unlock();
			throw e;
		}
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
		ensureDirectory(cataloguesDirectory, "generation catalogues");
		ensureDirectory(commitsDirectory, "generation commits");
		ensureDirectory(deltasDirectory, "generation deltas");
		ensureDirectory(objectsDirectory, "immutable objects");
		ensureDirectory(stagingDirectory, "generation staging");
	}

	private StorageReport measureStorageLocked() throws IOException {
		loadCurrentDeep();
		NavigableMap<String, StoredRecord> records = readStoredRecords();
		Map<String, Long> expectedSizes = new TreeMap<>();
		long objectReferences = 0;
		for (StoredRecord stored : records.values()) objectReferences = addExact(objectReferences, addReferences(stored.record(), expectedSizes), "object reference count");
		long referencedObjectBytes = verifyObjectReferences(expectedSizes);
		FileTotals recordFiles = recordFiles(records);
		FileTotals catalogueFiles = fileTotals(regularFiles(cataloguesDirectory, "generation catalogues"));
		FileTotals commitFiles = fileTotals(regularFiles(commitsDirectory, "generation commits"));
		FileTotals deltaFiles = fileTotals(regularFiles(deltasDirectory, "generation deltas"));
		FileTotals objectFiles = fileTotals(regularFiles(objectsDirectory, "immutable objects"));
		FileTotals stagingFiles = fileTotals(regularFiles(stagingDirectory, "generation staging"));
		return new StorageReport(records.size(), recordFiles.bytes(), catalogueFiles.count(), catalogueFiles.bytes(), commitFiles.count(), commitFiles.bytes(),
				deltaFiles.count(), deltaFiles.bytes(), objectFiles.count(), objectFiles.bytes(), stagingFiles.count(), stagingFiles.bytes(), expectedSizes.size(),
				referencedObjectBytes, objectReferences);
	}

	private CollectionResult collectUnreachableObjectsLocked(Set<String> generationPins, Set<String> objectPins) throws IOException {
		Optional<CurrentSnapshot> current = loadCurrentDeep();
		if (current.isEmpty()) throw new IOException("Cannot collect without a valid current generation");
		NavigableMap<String, StoredRecord> records = readStoredRecords();
		String currentModpackId = current.orElseThrow().record().manifest().modpackId();
		NavigableSet<String> retained = new TreeSet<>(generationPins);
		current.map(snapshot -> snapshot.record().metadata().generationId()).ifPresent(retained::add);
		Map<String, Long> expectedSizes = new TreeMap<>();
		for (String generationId : retained) {
			StoredRecord stored = records.get(generationId);
			if (stored == null) throw new IOException("Retained generation record is missing: " + generationId);
			if (!currentModpackId.equals(stored.record().manifest().modpackId()))
				throw new IOException("Retained generation belongs to a different modpack lineage: " + generationId);
			validateParentChain(stored.record());
			addReferences(stored.record(), expectedSizes);
		}
		verifyObjectReferences(expectedSizes);
		Set<String> reachable = new HashSet<>(expectedSizes.keySet());
		for (String objectHash : objectPins) {
			if (!reachable.contains(objectHash)) verifyPinnedObject(objectHash);
			reachable.add(objectHash);
		}
		List<Path> beforeFiles = regularFiles(objectsDirectory, "immutable objects");
		FileTotals before = fileTotals(beforeFiles);
		long deletedCount = 0;
		long deletedBytes = 0;
		for (Path object : beforeFiles) {
			String name = object.getFileName().toString();
			if (!isDigest(name) || reachable.contains(name) || !isValidCanonicalObject(object, name)) continue;
			long size = Files.size(object);
			if (Files.deleteIfExists(object)) {
				deletedCount = addExact(deletedCount, 1, "deleted object count");
				deletedBytes = addExact(deletedBytes, size, "deleted object bytes");
			}
		}
		if (deletedCount > 0) forceDirectory(objectsDirectory);
		FileTotals after = fileTotals(regularFiles(objectsDirectory, "immutable objects"));
		return new CollectionResult(before.bytes(), after.bytes(), before.count(), after.count(), deletedCount, deletedBytes);
	}

	private NavigableMap<String, StoredRecord> readStoredRecords() throws IOException {
		TreeMap<String, StoredRecord> records = new TreeMap<>();
		if (!Files.exists(recordsDirectory, LinkOption.NOFOLLOW_LINKS)) return records;
		requireDirectory(recordsDirectory, "generation records");
		try (var paths = Files.list(recordsDirectory)) {
			for (Path path : paths.sorted(Comparator.comparing(value -> value.getFileName().toString())).toList()) {
				ensureRegular(path, "generation record");
				String filename = path.getFileName().toString();
				if (filename.length() != 45 || !filename.endsWith(".json")) throw new IOException("Invalid generation record filename: " + path);
				String generationId = filename.substring(0, 40);
				if (!isDigest(generationId)) throw new IOException("Invalid generation record filename: " + path);
				GenerationRecord record = readRecord(path);
				if (!generationId.equals(record.metadata().generationId())) throw new IOException("Generation record filename does not match its identity: " + path);
				if (records.put(generationId, new StoredRecord(record, path)) != null) throw new IOException("Duplicate generation record: " + generationId);
			}
		}
		return records;
	}

	private FileTotals recordFiles(NavigableMap<String, StoredRecord> records) throws IOException {
		List<Path> paths = records.values().stream().map(StoredRecord::path).toList();
		return fileTotals(paths);
	}

	private List<Path> regularFiles(Path directory, String description) throws IOException {
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
		requireDirectory(directory, description);
		try (var paths = Files.list(directory)) {
			return paths.filter(path -> !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					.sorted(Comparator.comparing(value -> value.getFileName().toString())).toList();
		}
	}

	private FileTotals fileTotals(List<Path> paths) throws IOException {
		long bytes = 0;
		long count = 0;
		for (Path path : paths) {
			if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
			count = addExact(count, 1, "file count");
			bytes = addExact(bytes, Files.size(path), "file bytes");
		}
		return new FileTotals(count, bytes);
	}

	private long addManifestReferences(GenerationRecord record, Map<String, Long> expectedSizes) throws IOException {
		long count = 0;
		for (var group : record.manifest().groups().values()) for (var file : group.files().values()) {
			addExpectedSize(expectedSizes, file.sha1().toLowerCase(Locale.ROOT), file.size());
			count = addExact(count, 1, "object reference count");
		}
		return count;
	}

	private long addReferences(GenerationRecord record, Map<String, Long> expectedSizes) throws IOException {
		long count = addManifestReferences(record, expectedSizes);
		for (var entry : record.ownershipLedger().entries().values()) for (var content : entry.historicalHashes()) {
			addExpectedSize(expectedSizes, content.sha1(), content.size());
			count = addExact(count, 1, "object reference count");
		}
		return count;
	}

	private static void addExpectedSize(Map<String, Long> expectedSizes, String sha1, long expectedSize) throws IOException {
		if (!isDigest(sha1) || expectedSize < 0) throw new IOException("Invalid immutable object reference: " + sha1);
		Long previousSize = expectedSizes.putIfAbsent(sha1, expectedSize);
		if (previousSize != null && previousSize.longValue() != expectedSize)
			throw new IOException("Immutable object has conflicting advertised sizes: " + sha1);
	}

	private long verifyObjectReferences(Map<String, Long> expectedSizes) throws IOException {
		Set<String> verified = new HashSet<>();
		long bytes = 0;
		for (var entry : expectedSizes.entrySet()) {
			verifyObject(entry.getKey(), entry.getValue(), expectedSizes, verified);
			bytes = addExact(bytes, entry.getValue(), "referenced object bytes");
		}
		return bytes;
	}

	private void verifyPinnedObject(String sha1) throws IOException {
		Path object = objectPath(sha1);
		ensureRegular(object, "pinned immutable object " + sha1);
		if (!sha1.equals(HashUtils.getHash(object))) throw new IOException("Pinned immutable object failed SHA-1 verification: " + object);
	}

	private static NavigableSet<String> canonicalPins(Set<String> pins, String description) throws IOException {
		TreeSet<String> result = new TreeSet<>();
		for (String pin : pins) {
			if (!isDigest(pin)) throw new IOException("Invalid pinned " + description + " hash: " + pin);
			result.add(pin);
		}
		return result;
	}

	private static long addExact(long first, long second, String description) throws IOException {
		try {
			return Math.addExact(first, second);
		} catch (ArithmeticException e) {
			throw new IOException("Overflow while measuring " + description, e);
		}
	}

	private boolean isValidCanonicalObject(Path object, String name) {
		return !Files.isSymbolicLink(object) && Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS) && name.equals(HashUtils.getHash(object));
	}

	private Jsons.GenerationPointerFields readCurrentPointer() throws IOException {
		ensureRegular(currentPath, "current generation pointer");
		try {
			Jsons.GenerationPointerFields pointer = ConfigTools.parse(Files.readString(currentPath, StandardCharsets.UTF_8), Jsons.GenerationPointerFields.class);
			if (pointer.schemaVersion != CURRENT_POINTER_SCHEMA_VERSION || !isDigest(pointer.generationId))
				throw new IOException("Invalid current generation pointer metadata: " + currentPath);
			return pointer;
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Invalid current generation pointer: " + currentPath, e);
		}
	}

	private LoadedRecord readProjectionOrRecord(Path recordPath) throws IOException {
		IOException projectionFailure = null;
		if (Files.exists(currentProjectionPath, LinkOption.NOFOLLOW_LINKS)) {
			try {
				GenerationRecord projection = readRecord(currentProjectionPath);
				if (!projection.metadata().generationId().equals(recordGenerationId(recordPath)))
					throw new IOException("Current projection does not match the current generation identity: " + currentProjectionPath);
				return new LoadedRecord(projection, currentProjectionPath, false);
			} catch (IOException e) {
				projectionFailure = e;
			}
		}
		GenerationRecord record = readRecord(recordPath);
		if (projectionFailure != null)
			Constants.LOGGER.warn("Current generation projection is invalid; using the immutable record until it is repaired: {}", currentProjectionPath, projectionFailure);
		else
			Constants.LOGGER.debug("Current generation projection is missing; using the immutable record until it is repaired: {}", currentProjectionPath);
		return new LoadedRecord(record, recordPath, true);
	}

	private String recordGenerationId(Path path) throws IOException {
		String filename = path.getFileName().toString();
		if (filename.length() != 45 || !filename.endsWith(".json")) throw new IOException("Invalid generation record path: " + path);
		return filename.substring(0, 40);
	}

	private GenerationRecord readRecord(Path path) throws IOException {
		ensureRegular(path, "generation record");
		try {
			return GenerationRecord.fromFields(ConfigTools.parse(Files.readString(path, StandardCharsets.UTF_8), Jsons.CompleteModpackContentFields.class));
		} catch (RuntimeException e) {
			throw new IOException("Invalid generation record: " + path, e);
		}
	}

	private OwnershipDelta readDelta(String generationId) throws IOException {
		return readDelta(deltaPath(generationId));
	}

	private OwnershipDelta readDelta(Path path) throws IOException {
		ensureRegular(path, "generation ownership delta");
		try {
			return OwnershipDelta.fromFields(ConfigTools.parse(Files.readString(path, StandardCharsets.UTF_8), Jsons.OwnershipDeltaFields.class));
		} catch (RuntimeException e) {
			throw new IOException("Invalid generation ownership delta: " + path, e);
		}
	}

	private CatalogueSnapshot readCatalogue(String stateDigest) throws IOException {
		return readCatalogue(cataloguePath(stateDigest));
	}

	private CatalogueSnapshot readCatalogue(Path path) throws IOException {
		ensureRegular(path, "generation catalogue snapshot");
		try {
			CatalogueSnapshot snapshot = CatalogueSnapshot.fromFields(ConfigTools.parse(Files.readString(path, StandardCharsets.UTF_8), Jsons.CatalogueSnapshotFields.class));
			if (!snapshot.stateDigest().equals(catalogueStateDigest(path))) throw new IOException("Catalogue snapshot filename does not match its identity: " + path);
			return snapshot;
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Invalid generation catalogue snapshot: " + path, e);
		}
	}

	private GenerationCommit readCommit(String generationId) throws IOException {
		return readCommit(commitPath(generationId));
	}

	private GenerationCommit readCommit(Path path) throws IOException {
		ensureRegular(path, "generation commit");
		try {
			GenerationCommit commit = GenerationCommit.fromFields(ConfigTools.parse(Files.readString(path, StandardCharsets.UTF_8), Jsons.GenerationCommitFields.class));
			if (!commit.generationId().equals(commitGenerationId(path))) throw new IOException("Generation commit filename does not match its identity: " + path);
			return commit;
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Invalid generation commit: " + path, e);
		}
	}

	private void validateParentChain(GenerationRecord current) throws IOException {
		Set<String> visited = new HashSet<>();
		List<GenerationRecord> reverseChain = new ArrayList<>();
		GenerationRecord record = current;
		while (true) {
			String id = record.metadata().generationId();
			if (!visited.add(id)) throw new IOException("Generation parent cycle detected at " + id);
			reverseChain.add(record);
			String parent = record.metadata().parentGenerationId();
			if (parent.isEmpty()) break;
			Path parentPath = recordPath(parent);
			record = readRecord(parentPath);
			if (!record.metadata().generationId().equals(parent)) throw new IOException("Generation parent filename does not match its identity: " + parentPath);
			if (!record.manifest().modpackId().equals(current.manifest().modpackId()))
				throw new IOException("Generation parent modpack ID does not match current lineage: " + parent);
		}
		Collections.reverse(reverseChain);
		requireDirectory(cataloguesDirectory, "generation catalogues");
		requireDirectory(commitsDirectory, "generation commits");
		requireDirectory(deltasDirectory, "generation deltas");
		OwnershipLedger ledger = OwnershipLedger.empty(current.manifest().modpackId());
		for (GenerationRecord chainRecord : reverseChain) {
			CatalogueSnapshot snapshot = readCatalogue(chainRecord.metadata().stateDigest());
			if (!snapshot.manifest().equals(chainRecord.manifest()))
				throw new IOException("Generation catalogue does not match its immutable record: " + chainRecord.metadata().generationId());
			OwnershipDelta actualDelta = readDelta(chainRecord.metadata().generationId());
			OwnershipDelta expectedDelta = OwnershipDelta.between(ledger, chainRecord.manifest());
			if (!expectedDelta.equals(actualDelta))
				throw new IOException("Generation ownership delta does not match its parent and catalogue: " + chainRecord.metadata().generationId());
			GenerationCommit actualCommit = readCommit(chainRecord.metadata().generationId());
			if (!GenerationCommit.from(chainRecord, actualDelta).equals(actualCommit))
				throw new IOException("Generation commit does not match its record and ownership delta: " + chainRecord.metadata().generationId());
			try {
				ledger = OwnershipLedger.materialize(ledger, chainRecord.manifest(), chainRecord.metadata().generationId(), actualDelta);
			} catch (RuntimeException e) {
				throw new IOException("Generation ownership ledger does not match its parent chain", e);
			}
			if (!ledger.equals(chainRecord.ownershipLedger()))
				throw new IOException("Generation ownership ledger does not match its persisted ownership delta: " + chainRecord.metadata().generationId());
		}
	}

	private NavigableMap<String, Path> verifyActiveTargetObjects(GenerationRecord record) throws IOException {
		TreeMap<String, Long> expectedSizes = new TreeMap<>();
		addManifestReferences(record, expectedSizes);
		verifyObjectReferences(expectedSizes);
		return activeTargetPaths(expectedSizes);
	}

	private NavigableMap<String, Path> activeTargetPaths(GenerationRecord record) throws IOException {
		TreeMap<String, Long> expectedSizes = new TreeMap<>();
		addManifestReferences(record, expectedSizes);
		return activeTargetPaths(expectedSizes);
	}

	private NavigableMap<String, Path> activeTargetPaths(Map<String, Long> expectedSizes) throws IOException {
		TreeMap<String, Path> hosting = new TreeMap<>();
		for (String sha1 : expectedSizes.keySet()) hosting.put(sha1, objectPath(sha1));
		return hosting;
	}

	private void verifyAllReferencedObjects(GenerationRecord record) throws IOException {
		TreeMap<String, Long> expectedSizes = new TreeMap<>();
		addReferences(record, expectedSizes);
		verifyObjectReferences(expectedSizes);
	}

	private void verifyObject(String sha1, long expectedSize, Map<String, Long> expectedSizes, Set<String> verified) throws IOException {
		Path object = objectPath(sha1);
		Long previousSize = expectedSizes.putIfAbsent(sha1, expectedSize);
		if (previousSize != null && previousSize.longValue() != expectedSize)
			throw new IOException("Immutable object has conflicting advertised sizes: " + sha1);
		if (!verified.add(sha1)) return;
		ensureRegular(object, "immutable object " + sha1);
		if (Files.size(object) != expectedSize || !sha1.equals(HashUtils.getHash(object)))
			throw new IOException("Immutable object failed size/SHA-1 verification: " + object);
	}

	private Path objectPath(String sha1) throws IOException {
		if (!isDigest(sha1)) throw new IOException("Invalid immutable object SHA-1: " + sha1);
		Path object = objectsDirectory.resolve(sha1).normalize();
		if (!object.startsWith(objectsDirectory) || !objectsDirectory.equals(object.getParent()))
			throw new IOException("Object path escapes immutable object store: " + sha1);
		return object;
	}

	private Path recordPath(String generationId) throws IOException {
		if (!isDigest(generationId)) throw new IOException("Invalid generation ID: " + generationId);
		return recordsDirectory.resolve(generationId + ".json");
	}

	private Path deltaPath(String generationId) throws IOException {
		if (!isDigest(generationId)) throw new IOException("Invalid generation ID: " + generationId);
		return deltasDirectory.resolve(generationId + ".json");
	}

	private Path cataloguePath(String stateDigest) throws IOException {
		if (!isDigest(stateDigest)) throw new IOException("Invalid catalogue state digest: " + stateDigest);
		return cataloguesDirectory.resolve(stateDigest + ".json");
	}

	private Path commitPath(String generationId) throws IOException {
		if (!isDigest(generationId)) throw new IOException("Invalid generation ID: " + generationId);
		return commitsDirectory.resolve(generationId + ".json");
	}

	private String catalogueStateDigest(Path path) throws IOException {
		String filename = path.getFileName().toString();
		if (filename.length() != 45 || !filename.endsWith(".json")) throw new IOException("Invalid generation catalogue path: " + path);
		String stateDigest = filename.substring(0, 40);
		if (!isDigest(stateDigest)) throw new IOException("Invalid generation catalogue filename: " + path);
		return stateDigest;
	}

	private String commitGenerationId(Path path) throws IOException {
		String filename = path.getFileName().toString();
		if (filename.length() != 45 || !filename.endsWith(".json")) throw new IOException("Invalid generation commit path: " + path);
		String generationId = filename.substring(0, 40);
		if (!isDigest(generationId)) throw new IOException("Invalid generation commit filename: " + path);
		return generationId;
	}

	private static Jsons.GenerationPointerFields pointer(GenerationRecord record) {
		Jsons.GenerationPointerFields pointer = new Jsons.GenerationPointerFields();
		pointer.schemaVersion = CURRENT_POINTER_SCHEMA_VERSION;
		pointer.generationId = record.metadata().generationId();
		return pointer;
	}

	private void writeCurrentProjection(GenerationRecord record) throws IOException {
		ConfigTools.writeAtomic(currentProjectionPath, record.toFields());
	}

	private void writeRecordNoClobber(Path path, GenerationRecord record) throws IOException {
		writeImmutableJsonNoClobber(path, recordsDirectory, ".record-", record.toFields(), record, this::readRecord, "generation record");
	}

	private OwnershipDelta writeDeltaNoClobber(GenerationRecord record, GenerationRecord parent) throws IOException {
		OwnershipLedger base = parent == null ? OwnershipLedger.empty(record.manifest().modpackId()) : parent.ownershipLedger();
		OwnershipDelta delta = OwnershipDelta.between(base, record.manifest());
		Path path = deltaPath(record.metadata().generationId());
		writeImmutableJsonNoClobber(path, deltasDirectory, ".delta-", delta.toFields(), delta, this::readDelta, "generation ownership delta");
		return delta;
	}

	private void writeCatalogueNoClobber(GenerationRecord record) throws IOException {
		CatalogueSnapshot snapshot = CatalogueSnapshot.from(record.manifest());
		Path path = cataloguePath(snapshot.stateDigest());
		writeImmutableJsonNoClobber(path, cataloguesDirectory, ".catalogue-", snapshot.toFields(), snapshot, this::readCatalogue, "generation catalogue snapshot");
	}

	private void writeCommitNoClobber(GenerationRecord record, OwnershipDelta delta) throws IOException {
		GenerationCommit commit = GenerationCommit.from(record, delta);
		Path path = commitPath(commit.generationId());
		writeImmutableJsonNoClobber(path, commitsDirectory, ".commit-", commit.toFields(), commit, this::readCommit, "generation commit");
	}

	private <T> void writeImmutableJsonNoClobber(Path path, Path directory, String temporaryPrefix, Object value, T expected,
			ImmutableJsonReader<T> reader, String description) throws IOException {
		ensureDirectory(directory, description + "s");
		byte[] bytes = ConfigTools.GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			T existing = reader.read(path);
			if (!existing.equals(expected)) throw new IOException(description + " already exists with different content: " + path);
			return;
		}
		Path temporary = Files.createTempFile(directory, temporaryPrefix, ".tmp");
		try {
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) channel.write(buffer);
				channel.force(true);
			}
			try {
				Files.createLink(path, temporary);
				forceDirectory(directory);
			} catch (FileAlreadyExistsException e) {
				T existing = reader.read(path);
				if (!existing.equals(expected)) throw new IOException(description + " publication race: " + path, e);
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

	private record StoredRecord(GenerationRecord record, Path path) {}

	private record LoadedRecord(GenerationRecord record, Path path, boolean needsRepair) {}

	private record FileTotals(long count, long bytes) {}

	private static final class PublicationGuard implements AutoCloseable {
		private final ReentrantLock jvmLock;
		private final FileChannel channel;
		private final FileLock fileLock;

		private PublicationGuard(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
			this.jvmLock = jvmLock;
			this.channel = channel;
			this.fileLock = fileLock;
		}

		@Override
		public void close() throws IOException {
			try {
				fileLock.release();
			} finally {
				try {
					channel.close();
				} finally {
					jvmLock.unlock();
				}
			}
		}
	}

	private static NavigableMap<String, Path> immutablePaths(Map<String, Path> paths) {
		TreeMap<String, Path> sorted = new TreeMap<>();
		if (paths != null) for (var entry : paths.entrySet()) sorted.put(entry.getKey(), entry.getValue().toAbsolutePath().normalize());
		return Collections.unmodifiableNavigableMap(sorted);
	}
}
