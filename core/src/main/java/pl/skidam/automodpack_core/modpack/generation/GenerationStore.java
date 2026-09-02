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
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.utils.HashUtils;

public final class GenerationStore {
	public static final int CURRENT_POINTER_SCHEMA_VERSION = 1;

	public enum PublicationStatus {
		PUBLISHED, NO_CHANGES
	}

	public record CurrentSnapshot(GenerationRecord record, Path projectionPath, NavigableMap<String, Path> hostingPaths) {
		public CurrentSnapshot {
			record = Objects.requireNonNull(record);
			projectionPath = Objects.requireNonNull(projectionPath).toAbsolutePath().normalize();
			hostingPaths = immutablePaths(hostingPaths);
		}
	}

	public record Publication(PublicationStatus status, GenerationRecord record, Path projectionPath, NavigableMap<String, Path> hostingPaths) {
		public Publication {
			status = Objects.requireNonNull(status);
			record = Objects.requireNonNull(record);
			projectionPath = Objects.requireNonNull(projectionPath).toAbsolutePath().normalize();
			hostingPaths = immutablePaths(hostingPaths);
		}
	}

	/** A deterministic receipt for the regular files in the generation store. */
	public record StorageReport(long catalogueCount, long catalogueBytes, long commitCount, long commitBytes,
			long deltaCount, long deltaBytes, long immutableObjectCount, long immutableObjectBytes, long stagingFileCount, long stagingBytes,
			long referencedObjectCount, long referencedObjectBytes, long objectReferenceCount) {
		public StorageReport {
			if (catalogueCount < 0 || catalogueBytes < 0 || commitCount < 0 || commitBytes < 0 || deltaCount < 0
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
		GenerationRecord record;
		Path materializedPath = currentProjectionPath;
		LoadedProjection loaded = null;
		if (deepVerification) {
			record = readCompactState(pointer.generationId).record();
			if (Files.exists(currentProjectionPath, LinkOption.NOFOLLOW_LINKS)) {
				GenerationRecord projection = readProjection(currentProjectionPath);
				if (!projection.equals(record)) throw new IOException("Current projection does not match compact generation metadata: " + currentProjectionPath);
			}
		} else {
			loaded = readProjectionOrCompact(pointer.generationId);
			record = loaded.record();
			materializedPath = loaded.path();
		}
		if (!record.metadata().generationId().equals(pointer.generationId))
			throw new IOException("Current pointer does not match current generation identity: " + pointer.generationId);
		if (deepVerification) {
			verifyAllReferencedObjects(record);
		}
		NavigableMap<String, Path> hosting = deepVerification ? activeTargetPaths(record) : verifyActiveTargetObjects(record);
		if (repairProjection && loaded != null && loaded.needsRepair()) {
			writeCurrentProjection(record);
			materializedPath = currentProjectionPath;
		}
		if (Files.exists(materializedPath, LinkOption.NOFOLLOW_LINKS)) hosting.put("", materializedPath);
		return Optional.of(new CurrentSnapshot(record, materializedPath, hosting));
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
			GenerationHistoryEntry target = findAncestor(previous, targetGenerationId);
			if (target == null) throw new IOException("Rollback target is not in the current generation history: " + targetGenerationId);
			ensureStoreDirectories();
			GenerationRecord record = GenerationRecord.create(target.manifest(), previous, clock.instant(), patchNotes, targetGenerationId);
			OwnershipDelta delta = writeDeltaNoClobber(record, previous);
			writeCatalogueNoClobber(record);
			writeCommitNoClobber(record, delta);
			NavigableMap<String, Path> hosting = verifyActiveTargetObjects(record);
			writeCurrentProjection(record);
			hosting.put("", currentProjectionPath);
			commitHook.beforeCurrentPointerReplacement();
			ensureCurrentStillMatches(expectedCurrent);
			ConfigTools.writeAtomic(currentPath, pointer(record));
			return new Publication(PublicationStatus.PUBLISHED, record, currentProjectionPath, hosting);
		}
	}

	public List<GenerationHistoryEntry> currentHistory() throws IOException {
		Optional<CurrentSnapshot> current = loadCurrent();
		if (current.isEmpty()) return List.of();
		return readCompactState(current.orElseThrow().record().metadata().generationId()).entries();
	}

	private GenerationHistoryEntry findAncestor(GenerationRecord current, String targetGenerationId) throws IOException {
		for (GenerationHistoryEntry entry : readCompactState(current.metadata().generationId()).entries())
			if (entry.metadata().generationId().equals(targetGenerationId)) return entry;
		return null;
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
		OwnershipDelta delta = writeDeltaNoClobber(record, previous);
		writeCatalogueNoClobber(record);
		writeCommitNoClobber(record, delta);
		NavigableMap<String, Path> hosting = verifyActiveTargetObjects(record);
		writeCurrentProjection(record);
		hosting.put("", currentProjectionPath);
		Publication publication = new Publication(PublicationStatus.PUBLISHED, record, currentProjectionPath, hosting);
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
		return new Publication(status, snapshot.record(), snapshot.projectionPath(), snapshot.hostingPaths());
	}

	private void ensureExpected(Optional<CurrentSnapshot> expected, Optional<CurrentSnapshot> actual) throws IOException {
		if (expected.isPresent() != actual.isPresent()) throw new IOException("Current generation changed before publication");
		if (expected.isPresent() && !expected.get().record().metadata().generationId().equals(actual.get().record().metadata().generationId()))
			throw new IOException("Current generation changed before publication");
	}

	private void ensureCurrentStillMatches(Optional<CurrentSnapshot> expected) throws IOException {
		Optional<String> actualGenerationId = Files.exists(currentPath, LinkOption.NOFOLLOW_LINKS) ? Optional.of(readCurrentPointer().generationId) : Optional.empty();
		if (expected.isPresent() != actualGenerationId.isPresent()) throw new IOException("Current generation changed before publication");
		if (expected.isPresent() && !expected.orElseThrow().record().metadata().generationId().equals(actualGenerationId.orElseThrow()))
			throw new IOException("Current generation changed before publication");
	}

	private void ensureStoreDirectories() throws IOException {
		ensureDirectory(root, "generation store");
		ensureDirectory(cataloguesDirectory, "generation catalogues");
		ensureDirectory(commitsDirectory, "generation commits");
		ensureDirectory(deltasDirectory, "generation deltas");
		ensureDirectory(objectsDirectory, "immutable objects");
		ensureDirectory(stagingDirectory, "generation staging");
	}

	private StorageReport measureStorageLocked() throws IOException {
		GenerationRecord current = loadCurrentDeep().map(CurrentSnapshot::record).orElse(null);
		Map<String, Long> expectedSizes = new TreeMap<>();
		long objectReferences = 0;
		if (current != null) objectReferences = addExact(objectReferences, addReferences(current, expectedSizes), "object reference count");
		long referencedObjectBytes = verifyObjectReferences(expectedSizes);
		FileTotals catalogueFiles = fileTotals(regularFiles(cataloguesDirectory, "generation catalogues"));
		FileTotals commitFiles = fileTotals(regularFiles(commitsDirectory, "generation commits"));
		FileTotals deltaFiles = fileTotals(regularFiles(deltasDirectory, "generation deltas"));
		FileTotals objectFiles = fileTotals(regularFiles(objectsDirectory, "immutable objects"));
		FileTotals stagingFiles = fileTotals(regularFiles(stagingDirectory, "generation staging"));
		return new StorageReport(catalogueFiles.count(), catalogueFiles.bytes(), commitFiles.count(), commitFiles.bytes(),
				deltaFiles.count(), deltaFiles.bytes(), objectFiles.count(), objectFiles.bytes(), stagingFiles.count(), stagingFiles.bytes(), expectedSizes.size(),
				referencedObjectBytes, objectReferences);
	}

	private CollectionResult collectUnreachableObjectsLocked(Set<String> generationPins, Set<String> objectPins) throws IOException {
		Optional<CurrentSnapshot> current = loadCurrentDeep();
		if (current.isEmpty()) throw new IOException("Cannot collect without a valid current generation");
		String currentGenerationId = current.orElseThrow().record().metadata().generationId();
		CompactHistory history = readCompactHistory(currentGenerationId);
		NavigableSet<String> retained = new TreeSet<>(generationPins);
		retained.add(currentGenerationId);
		Map<String, Long> expectedSizes = new TreeMap<>();
		OwnershipLedger.Builder ledger = OwnershipLedger.builder(history.generations().get(0).commit().modpackId());
		for (CompactGeneration generation : history.generations()) {
			try {
				ledger.apply(generation.delta(), generation.commit().generationId());
			} catch (RuntimeException e) {
				throw new IOException("Generation ownership delta cannot be applied: " + generation.commit().generationId(), e);
			}
			if (retained.contains(generation.commit().generationId())) {
				addManifestReferences(generation.snapshot().manifest(), expectedSizes);
				addLedgerReferences(ledger.entriesView().values(), expectedSizes);
			}
		}
		for (String generationId : generationPins) {
			if (history.generations().stream().noneMatch(generation -> generation.commit().generationId().equals(generationId)))
				throw new IOException("Retained generation is not in the current lineage: " + generationId);
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
		return addManifestReferences(record.manifest(), expectedSizes);
	}

	private long addManifestReferences(GroupManifest manifest, Map<String, Long> expectedSizes) throws IOException {
		long count = 0;
		for (var group : manifest.groups().values()) for (var file : group.files().values()) {
			addExpectedSize(expectedSizes, file.sha1().toLowerCase(Locale.ROOT), file.size());
			count = addExact(count, 1, "object reference count");
		}
		return count;
	}

	private long addReferences(GenerationRecord record, Map<String, Long> expectedSizes) throws IOException {
		long count = addManifestReferences(record, expectedSizes);
		return addLedgerReferences(record.ownershipLedger().entries().values(), expectedSizes, count);
	}

	private long addLedgerReferences(Collection<OwnershipLedger.Entry> entries, Map<String, Long> expectedSizes) throws IOException {
		return addLedgerReferences(entries, expectedSizes, 0);
	}

	private long addLedgerReferences(Collection<OwnershipLedger.Entry> entries, Map<String, Long> expectedSizes, long count) throws IOException {
		for (var entry : entries) for (var content : entry.historicalHashes()) {
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

	private LoadedProjection readProjectionOrCompact(String generationId) throws IOException {
		IOException projectionFailure = null;
		if (Files.exists(currentProjectionPath, LinkOption.NOFOLLOW_LINKS)) {
			try {
				GenerationRecord projection = readProjection(currentProjectionPath);
				if (!projection.metadata().generationId().equals(generationId))
					throw new IOException("Current projection does not match the current generation identity: " + currentProjectionPath);
				return new LoadedProjection(projection, currentProjectionPath, false);
			} catch (IOException e) {
				projectionFailure = e;
			}
		}
		GenerationRecord record = readCompactRecord(generationId);
		if (projectionFailure != null)
			Constants.LOGGER.warn("Current generation projection is invalid; using durable generation state until it is repaired: {}", currentProjectionPath, projectionFailure);
		else
			Constants.LOGGER.debug("Current generation projection is missing; rebuilding it from compact metadata: {}", currentProjectionPath);
		return new LoadedProjection(record, currentProjectionPath, true);
	}

	private GenerationRecord readProjection(Path path) throws IOException {
		ensureRegular(path, "current generation projection");
		try {
			return GenerationRecord.fromFields(ConfigTools.parse(Files.readString(path, StandardCharsets.UTF_8), Jsons.CompleteModpackContentFields.class));
		} catch (RuntimeException e) {
			throw new IOException("Invalid current generation projection: " + path, e);
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

	private GenerationRecord readCompactRecord(String generationId) throws IOException {
		return readCompactState(generationId).record();
	}

	private CompactHistory readCompactHistory(String generationId) throws IOException {
		requireDirectory(cataloguesDirectory, "generation catalogues");
		requireDirectory(commitsDirectory, "generation commits");
		requireDirectory(deltasDirectory, "generation deltas");
		Set<String> visited = new HashSet<>();
		List<CompactGeneration> reverse = new ArrayList<>();
		String currentId = generationId;
		while (true) {
			if (!visited.add(currentId)) throw new IOException("Generation parent cycle detected at " + currentId);
			GenerationCommit commit = readCommit(currentId);
			CatalogueSnapshot snapshot = readCatalogue(commit.stateDigest());
			if (!commit.modpackId().equals(snapshot.manifest().modpackId()) || !commit.stateDigest().equals(snapshot.stateDigest()))
				throw new IOException("Generation commit does not match its catalogue snapshot: " + currentId);
			OwnershipDelta delta = readDelta(currentId);
			if (!commit.ownershipDeltaDigest().equals(delta.digest()) || !commit.modpackId().equals(delta.modpackId()))
				throw new IOException("Generation commit does not match its ownership delta: " + currentId);
			reverse.add(new CompactGeneration(commit, snapshot, delta));
			String parent = commit.parentGenerationId();
			if (parent.isEmpty()) break;
			currentId = parent;
		}
		Collections.reverse(reverse);
		if (reverse.isEmpty()) throw new IOException("Generation compact parent chain is empty");
		List<GenerationHistoryEntry> entries = new ArrayList<>();
		String expectedParent = GenerationMetadata.ROOT_PARENT;
		for (CompactGeneration compact : reverse) {
			GenerationCommit commit = compact.commit();
			if (!commit.parentGenerationId().equals(expectedParent))
				throw new IOException("Generation compact parent chain is not ordered at: " + commit.generationId());
			try {
				entries.add(GenerationHistoryEntry.from(commit, compact.snapshot()));
			} catch (RuntimeException e) {
				throw new IOException("Generation compact metadata does not form a valid history entry: " + commit.generationId(), e);
			}
			expectedParent = commit.generationId();
		}
		return new CompactHistory(List.copyOf(reverse), List.copyOf(entries));
	}

	private CompactState readCompactState(String generationId) throws IOException {
		CompactHistory history = readCompactHistory(generationId);
		OwnershipLedger.Builder ledger = OwnershipLedger.builder(history.generations().get(0).commit().modpackId());
		for (CompactGeneration generation : history.generations()) {
			try {
				ledger.apply(generation.delta(), generation.commit().generationId());
			} catch (RuntimeException e) {
				throw new IOException("Generation ownership delta cannot be applied: " + generation.commit().generationId(), e);
			}
		}
		CompactGeneration current = history.generations().get(history.generations().size() - 1);
		OwnershipLedger materialized;
		try {
			materialized = ledger.build();
		} catch (RuntimeException e) {
			throw new IOException("Generation ownership ledger cannot be reconstructed from compact metadata", e);
		}
		if (!materialized.digest().equals(current.commit().ledgerDigest()))
			throw new IOException("Current compact ledger digest does not match reconstructed ownership state: " + generationId);
		GenerationRecord record;
		try {
			record = new GenerationRecord(current.snapshot().manifest(), current.commit().metadata(), materialized);
		} catch (RuntimeException e) {
			throw new IOException("Current compact metadata does not form a valid record: " + generationId, e);
		}
		if (!GenerationCommit.from(record, current.delta()).equals(current.commit()))
			throw new IOException("Current compact commit does not match reconstructed record: " + generationId);
		return new CompactState(record, history.entries());
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

	private record CompactGeneration(GenerationCommit commit, CatalogueSnapshot snapshot, OwnershipDelta delta) {}

	private record CompactHistory(List<CompactGeneration> generations, List<GenerationHistoryEntry> entries) {}

	private record CompactState(GenerationRecord record, List<GenerationHistoryEntry> entries) {}

	private record LoadedProjection(GenerationRecord record, Path path, boolean needsRepair) {}

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
