package pl.skidam.automodpack_core.modpack.generation;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.ServerObjectStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.SharedObjectOwnership;
import pl.skidam.automodpack_core.storage.StoragePaths;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ImmutableFilePublisher;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

public final class GenerationStore {
	public static final int CURRENT_POINTER_SCHEMA_VERSION = 1;

	public enum PublicationStatus {
		PUBLISHED, NO_CHANGES
	}

	public record CurrentSnapshot(GenerationRecord record, Path projectionPath, GenerationHosting hostingPaths) {
		public CurrentSnapshot {
			record = Objects.requireNonNull(record);
			projectionPath = Objects.requireNonNull(projectionPath).toAbsolutePath().normalize();
			hostingPaths = Objects.requireNonNull(hostingPaths);
		}

		public CurrentSnapshot(GenerationRecord record, Path projectionPath, Map<String, Path> hostingPaths) {
			this(record, projectionPath, new GenerationHosting(hostingPaths));
		}
	}

	public record Publication(PublicationStatus status, GenerationRecord record, Path projectionPath, GenerationHosting hostingPaths) {
		public Publication {
			status = Objects.requireNonNull(status);
			record = Objects.requireNonNull(record);
			projectionPath = Objects.requireNonNull(projectionPath).toAbsolutePath().normalize();
			hostingPaths = Objects.requireNonNull(hostingPaths);
		}

		public Publication(PublicationStatus status, GenerationRecord record, Path projectionPath, Map<String, Path> hostingPaths) {
			this(status, record, projectionPath, new GenerationHosting(hostingPaths));
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
	}

	/** The result of one explicitly requested object collection pass. */
	public record CollectionResult(long beforeObjectBytes, long afterObjectBytes, long beforeObjectCount, long afterObjectCount,
			long deletedObjectCount, long deletedObjectBytes) {
		public CollectionResult {
			if (beforeObjectBytes < 0 || afterObjectBytes < 0 || beforeObjectCount < 0 || afterObjectCount < 0 || deletedObjectCount < 0 || deletedObjectBytes < 0)
				throw new IllegalArgumentException("Collection result values cannot be negative");
		}
	}

	/** A deterministic receipt for one explicit server generation-history compaction pass. */
	public record CompactionPreview(String boundaryGenerationId, List<String> rollbackUnavailableGenerationIds, List<String> supersededGenerationIds,
			List<String> supersededCatalogueStateDigests, long reclaimableCatalogueBytes, long reclaimableCommitBytes, long reclaimableDeltaBytes) {
		public CompactionPreview {
			boundaryGenerationId = GenerationMetadata.requireDigest(boundaryGenerationId, "compaction boundary generation ID");
			rollbackUnavailableGenerationIds = CompactionResult.canonicalReceiptIds(rollbackUnavailableGenerationIds, "rollback-unavailable generation ID");
			supersededGenerationIds = CompactionResult.canonicalReceiptIds(supersededGenerationIds, "superseded generation ID");
			supersededCatalogueStateDigests = CompactionResult.canonicalReceiptIds(supersededCatalogueStateDigests, "superseded catalogue state digest");
			if (reclaimableCatalogueBytes < 0 || reclaimableCommitBytes < 0 || reclaimableDeltaBytes < 0)
				throw new IllegalArgumentException("Compaction preview byte values cannot be negative");
			if (!rollbackUnavailableGenerationIds.equals(supersededGenerationIds))
				throw new IllegalArgumentException("Compaction rollback and superseded generation receipts disagree");
			if (supersededGenerationIds.contains(boundaryGenerationId)) throw new IllegalArgumentException("Compaction boundary is superseded");
		}

		public long reclaimableBytes() {
			try {
				return Math.addExact(Math.addExact(reclaimableCatalogueBytes, reclaimableCommitBytes), reclaimableDeltaBytes);
			} catch (ArithmeticException e) {
				throw new IllegalStateException("Compaction preview byte total overflowed", e);
			}
		}
	}

	public record CompactionResult(String boundaryGenerationId, List<String> supersededGenerationIds, List<String> supersededCatalogueStateDigests,
			long deletedCatalogueCount, long deletedCommitCount, long deletedDeltaCount, long deletedCatalogueBytes, long deletedCommitBytes, long deletedDeltaBytes) {
		public CompactionResult {
			boundaryGenerationId = GenerationMetadata.requireDigest(boundaryGenerationId, "compaction boundary generation ID");
			supersededGenerationIds = canonicalReceiptIds(supersededGenerationIds, "superseded generation ID");
			supersededCatalogueStateDigests = canonicalReceiptIds(supersededCatalogueStateDigests, "superseded catalogue state digest");
			if (List.of(deletedCatalogueCount, deletedCommitCount, deletedDeltaCount, deletedCatalogueBytes, deletedCommitBytes, deletedDeltaBytes).stream().anyMatch(value -> value < 0))
				throw new IllegalArgumentException("Compaction receipt values cannot be negative");
			if (supersededGenerationIds.contains(boundaryGenerationId)) throw new IllegalArgumentException("Compaction boundary is superseded");
		}

		public long deletedBytes() {
			try {
				return Math.addExact(Math.addExact(deletedCatalogueBytes, deletedCommitBytes), deletedDeltaBytes);
			} catch (ArithmeticException e) {
				throw new IllegalStateException("Compaction deleted byte total overflowed", e);
			}
		}

		private static List<String> canonicalReceiptIds(List<String> values, String description) {
			Objects.requireNonNull(values, description);
			return values.stream().map(value -> GenerationMetadata.requireDigest(value, description)).distinct().sorted().toList();
		}
	}

	@FunctionalInterface
	interface CommitHook {
		void beforeCurrentPointerReplacement() throws IOException;
	}

	@FunctionalInterface
	interface CompactionDeleteHook {
		void beforeDelete(Path path) throws IOException;
	}

	@FunctionalInterface
	private interface ImmutableJsonReader<T> {
		T read(Path path) throws IOException;
	}

	private static final CommitHook NOOP_HOOK = () -> {};
	private static final CompactionDeleteHook NOOP_COMPACTION_DELETE_HOOK = path -> {};
	private static final PublicationLockRegistry PUBLICATION_LOCKS = new PublicationLockRegistry();
	private final Path root;
	private final Path currentPath;
	private final Path currentProjectionPath;
	private final Path checkpointPath;
	private final Path publicationLockPath;
	private final Path cataloguesDirectory;
	private final Path commitsDirectory;
	private final Path deltasDirectory;
	private final Path objectsDirectory;
	private final Path stagingDirectory;
	private final ServerObjectStore objectStore;
	private final DataRootResolver.Location dataLocation;
	private final Clock clock;
	private final CommitHook commitHook;
	private final CompactionDeleteHook compactionDeleteHook;

	public GenerationStore(Path root) {
		this(root, root.resolve("objects"), Clock.systemUTC(), NOOP_HOOK, NOOP_COMPACTION_DELETE_HOOK);
	}

	GenerationStore(Path root, Clock clock, CommitHook commitHook) {
		this(root, root.resolve("objects"), clock, commitHook, NOOP_COMPACTION_DELETE_HOOK);
	}

	GenerationStore(Path root, Clock clock, CommitHook commitHook, CompactionDeleteHook compactionDeleteHook) {
		this(root, root.resolve("objects"), clock, commitHook, compactionDeleteHook);
	}

	public GenerationStore(Path root, Path objectsDirectory) {
		this(root, objectsDirectory, Clock.systemUTC(), NOOP_HOOK, NOOP_COMPACTION_DELETE_HOOK);
	}

	public GenerationStore(Path root, DataRootResolver.Location dataLocation) {
		this(root, dataLocation.layout().objectsDirectory(), Clock.systemUTC(), NOOP_HOOK, NOOP_COMPACTION_DELETE_HOOK, dataLocation);
	}

	GenerationStore(Path root, Path objectsDirectory, Clock clock, CommitHook commitHook) {
		this(root, objectsDirectory, clock, commitHook, NOOP_COMPACTION_DELETE_HOOK);
	}

	GenerationStore(Path root, Path objectsDirectory, Clock clock, CommitHook commitHook, CompactionDeleteHook compactionDeleteHook) {
		this(root, objectsDirectory, clock, commitHook, compactionDeleteHook, null);
	}

	private GenerationStore(Path root, Path objectsDirectory, Clock clock, CommitHook commitHook, CompactionDeleteHook compactionDeleteHook,
			DataRootResolver.Location dataLocation) {
		this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
		this.currentPath = this.root.resolve(StoragePaths.SERVER_CURRENT_FILE.getFileName().toString());
		this.currentProjectionPath = this.root.resolve(StoragePaths.SERVER_CURRENT_PROJECTION_FILE.getFileName().toString());
		this.checkpointPath = this.root.resolve(StoragePaths.SERVER_GENERATION_CHECKPOINT_FILE.getFileName().toString());
		this.publicationLockPath = this.root.resolve(StoragePaths.SERVER_PUBLICATION_LOCK_FILE.getFileName().toString());
		this.cataloguesDirectory = this.root.resolve(StoragePaths.SERVER_CATALOGUES_DIR.getFileName().toString());
		this.commitsDirectory = this.root.resolve(StoragePaths.SERVER_COMMITS_DIR.getFileName().toString());
		this.deltasDirectory = this.root.resolve(StoragePaths.SERVER_DELTAS_DIR.getFileName().toString());
		this.objectsDirectory = Objects.requireNonNull(objectsDirectory).toAbsolutePath().normalize();
		this.stagingDirectory = this.root.resolve(StoragePaths.SERVER_STAGING_DIR.getFileName().toString());
		this.clock = Objects.requireNonNull(clock);
		this.commitHook = Objects.requireNonNull(commitHook);
		this.compactionDeleteHook = Objects.requireNonNull(compactionDeleteHook);
		this.objectStore = new ServerObjectStore(objectsDirectory, stagingDirectory);
		this.dataLocation = dataLocation;
	}

	public Path objectRoot() {
		return objectsDirectory;
	}

	/** Measures the current generation store without publishing or deleting managed state. */
	public StorageReport measureStorage() throws IOException {
		FileTrees.createManagedDirectory(root, "generation store");
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
		FileTrees.createManagedDirectory(root, "generation store");
		try (PublicationGuard ignored = acquirePublicationGuard()) {
			recoverCompactionLocked();
			return collectUnreachableObjectsLocked(generationPins, objectPins);
		}
	}

	/** Returns an exact, non-mutating receipt for compacting details before a retained boundary. */
	public CompactionPreview previewCompaction(String boundaryGenerationId) throws IOException {
		FileTrees.createManagedDirectory(root, "generation store");
		try (PublicationGuard ignored = acquirePublicationGuard()) {
			return previewCompactionLocked(boundaryGenerationId);
		}
	}

	/** Explicitly removes detailed server state before a validated retained boundary. */
	public CompactionResult compactBefore(String boundaryGenerationId) throws IOException {
		FileTrees.createManagedDirectory(root, "generation store");
		try (PublicationGuard ignored = acquirePublicationGuard()) {
			return compactBeforeLocked(boundaryGenerationId);
		}
	}

	/** Returns the complete thin lineage, including entries whose detailed state was compacted. */
	public Optional<GenerationHistoryIndex> currentHistoryIndex() throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		try (PublicationGuard ignored = acquirePublicationGuard()) {
			recoverCompactionLocked();
			return currentHistoryIndexLocked();
		}
	}

	private Optional<GenerationHistoryIndex> currentHistoryIndexLocked() throws IOException {
		if (!Files.exists(currentPath, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		readCheckpoint();
		GenerationJsons.GenerationPointerFields pointer = readCurrentPointer();
		LoadedProjection loaded = readProjectionOrCompact(pointer.generationId);
		if (!loaded.record().metadata().generationId().equals(pointer.generationId)) throw new IOException("Current pointer does not match current generation identity: " + pointer.generationId);
		return Optional.of(loaded.historyIndex() == null ? historyIndex(pointer.generationId) : loaded.historyIndex());
	}

	/** Loads the current materialized projection and verifies only the active target objects. */
	public Optional<CurrentSnapshot> loadCurrent() throws IOException {
		return loadCurrentGuarded(false, false);
	}

	/** Performs an explicit ancestry and historical-object verification pass. */
	public Optional<CurrentSnapshot> loadCurrentDeep() throws IOException {
		return loadCurrentGuarded(true, false);
	}

	/** Repairs a missing or invalid projection under the publication lock before returning the active hosting map. */
	public Optional<CurrentSnapshot> loadCurrentAndRepair() throws IOException {
		FileTrees.createManagedDirectory(root, "generation store");
		try (PublicationGuard ignored = acquirePublicationGuard()) {
			recoverCompactionLocked();
			return loadCurrentState(false, true);
		}
	}

	private Optional<CurrentSnapshot> loadCurrentGuarded(boolean deepVerification, boolean repairProjection) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		try (PublicationGuard ignored = acquirePublicationGuard()) {
			recoverCompactionLocked();
			return loadCurrentState(deepVerification, repairProjection);
		}
	}

	private Optional<CurrentSnapshot> loadCurrentState(boolean deepVerification, boolean repairProjection) throws IOException {
		if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) FileTrees.requireDirectory(root, "generation store");
		if (!Files.exists(currentPath, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		readCheckpoint();
		GenerationJsons.GenerationPointerFields pointer = readCurrentPointer();
		GenerationRecord record;
		Path materializedPath = currentProjectionPath;
		LoadedProjection loaded = null;
		if (deepVerification) {
			record = readCompactState(pointer.generationId).record();
			if (Files.exists(currentProjectionPath, LinkOption.NOFOLLOW_LINKS)) {
				GenerationRecord projection = readProjection(currentProjectionPath);
				if (!projection.equals(record)) throw new IOException("Current projection does not match compact generation metadata: " + currentProjectionPath);
				if (readProjectionHistoryIndex(currentProjectionPath).isEmpty()) throw new IOException("Current projection is missing its generation history index: " + currentProjectionPath);
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
		GenerationHistoryIndex index = deepVerification
				? historyIndex(pointer.generationId)
				: loaded != null && loaded.historyIndex() != null
						? loaded.historyIndex()
						: historyIndex(pointer.generationId);
		NavigableMap<String, Path> hosting = deepVerification ? activeTargetPaths(record) : verifyActiveTargetObjects(record);
		hosting.putAll(historicalCataloguePaths(index));
		if (repairProjection && loaded != null && loaded.needsRepair()) {
			writeCurrentProjection(record);
			materializedPath = currentProjectionPath;
		}
		boolean projectionReady = loaded == null || !loaded.needsRepair() || repairProjection;
		if (projectionReady && Files.exists(materializedPath, LinkOption.NOFOLLOW_LINKS)) hosting.put("", materializedPath);
		return Optional.of(new CurrentSnapshot(record, materializedPath, hosting));
	}

	public Publication publish(ModpackCandidate candidate, Optional<CurrentSnapshot> expectedCurrent, String patchNotes) throws IOException {
		FileTrees.createManagedDirectory(root, "generation store");
		try (PublicationGuard ignored = acquirePublicationGuard()) {
			recoverCompactionLocked();
			return publishLocked(candidate, expectedCurrent, patchNotes);
		}
	}

	public Publication publishRevert(String targetGenerationId, Optional<CurrentSnapshot> expectedCurrent, String patchNotes) throws IOException {
		if (!isDigest(targetGenerationId)) throw new IOException("Invalid rollback target generation ID: " + targetGenerationId);
		Objects.requireNonNull(expectedCurrent, "expectedCurrent");
		FileTrees.createManagedDirectory(root, "generation store");
		try (PublicationGuard ignored = acquirePublicationGuard()) {
			recoverCompactionLocked();
			Optional<CurrentSnapshot> actualBefore = loadCurrentState(false, false);
			requireExpected(expectedCurrent, actualBefore);
			GenerationRecord previous = actualBefore.map(CurrentSnapshot::record).orElseThrow(() -> new IOException("Cannot revert before the root generation is published"));
			GenerationHistoryEntry target = findAncestor(previous, targetGenerationId);
			if (target == null) throw new IOException("Rollback target is not in the current generation history: " + targetGenerationId);
			createStoreDirectories();
			GenerationRecord record = GenerationRecord.create(target.manifest(), previous, clock.instant(), patchNotes, targetGenerationId);
			OwnershipDelta delta = writeDeltaNoClobber(record, previous);
			writeCatalogueNoClobber(record);
			writeCommitNoClobber(record, delta);
			NavigableMap<String, Path> hosting = verifyActiveTargetObjects(record);
			hosting.putAll(historicalCataloguePaths(historyIndex(record.metadata().generationId())));
			writeCurrentProjection(record);
			hosting.put("", currentProjectionPath);
			commitHook.beforeCurrentPointerReplacement();
			requireCurrentStillMatches(expectedCurrent);
			ConfigTools.writeAtomic(currentPath, pointer(record));
			return new Publication(PublicationStatus.PUBLISHED, record, currentProjectionPath, hosting);
		}
	}

	public List<GenerationHistoryEntry> currentHistory() throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
		try (PublicationGuard ignored = acquirePublicationGuard()) {
			recoverCompactionLocked();
			Optional<CurrentSnapshot> current = loadCurrentState(false, false);
			if (current.isEmpty()) return List.of();
			return readCompactState(current.orElseThrow().record().metadata().generationId()).entries();
		}
	}

	private GenerationHistoryIndex historyIndex(String generationId) throws IOException {
		return historyIndex(readCompactHistory(generationId));
	}

	private GenerationHistoryIndex historyIndex(CompactHistory history) throws IOException {
		GenerationCheckpoint checkpoint = readCheckpoint().orElse(null);
		if (checkpoint == null) return GenerationHistoryIndex.fromHistory(history.entries().get(0).manifest().modpackId(), history.entries());
		return checkpoint.historyIndex().append(history.entries());
	}

	private NavigableMap<String, Path> historicalCataloguePaths(GenerationHistoryIndex index) throws IOException {
		TreeMap<String, Path> paths = new TreeMap<>();
		for (GenerationHistoryIndex.Entry entry : index.entries()) {
			if (!entry.detailsAvailable()) continue;
			Path path = cataloguePath(entry.stateDigest());
			if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
			FileTrees.requireRegularFile(path, "generation catalogue");
			paths.put(GenerationHistoryIndex.catalogueRequestKey(entry.stateDigest()), path);
		}
		return paths;
	}

	private GenerationHistoryEntry findAncestor(GenerationRecord current, String targetGenerationId) throws IOException {
		for (GenerationHistoryEntry entry : readCompactState(current.metadata().generationId()).entries())
			if (entry.metadata().generationId().equals(targetGenerationId)) return entry;
		return null;
	}

	private Publication publishLocked(ModpackCandidate candidate, Optional<CurrentSnapshot> expectedCurrent, String patchNotes) throws IOException {
		Objects.requireNonNull(candidate, "candidate");
		Objects.requireNonNull(expectedCurrent, "expectedCurrent");
		Optional<CurrentSnapshot> actualBefore = loadCurrentState(false, false);
		requireExpected(expectedCurrent, actualBefore);
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
			if (previous.metadata().ledgerDigest().equals(candidateLedger.digest())
					&& previous.metadata().patchNotesDigest().equals(GenerationIdentity.patchNotesDigest(patchNotes)))
				return publication(PublicationStatus.NO_CHANGES, actualBefore.orElseThrow());
		}

		createStoreDirectories();
		GenerationRecord record = GenerationRecord.create(candidate.manifest(), previous, clock.instant(), patchNotes);
		publishOwnershipWith(candidate.manifest());
		try (FileMetadataCache cache = openMetadataCache()) {
			objectStore.promoteAll(candidate.objects(), cache);
		}
		OwnershipDelta delta = writeDeltaNoClobber(record, previous);
		writeCatalogueNoClobber(record);
		writeCommitNoClobber(record, delta);
		NavigableMap<String, Path> hosting = verifyActiveTargetObjects(record);
		hosting.putAll(historicalCataloguePaths(historyIndex(record.metadata().generationId())));
		writeCurrentProjection(record);
		hosting.put("", currentProjectionPath);
		Publication publication = new Publication(PublicationStatus.PUBLISHED, record, currentProjectionPath, hosting);
		GenerationJsons.GenerationPointerFields nextPointer = pointer(record);
		commitHook.beforeCurrentPointerReplacement();
		requireCurrentStillMatches(expectedCurrent);
		ConfigTools.writeAtomic(currentPath, nextPointer);
		return publication;
	}

	private void publishOwnershipWith(GroupManifest additionalManifest) throws IOException {
		if (dataLocation == null) return;
		TreeSet<String> hashes = currentOwnershipHashes();
		addManifestHashes(additionalManifest, hashes);
		SharedObjectOwnership.publish(dataLocation, "server", hashes);
	}

	private void publishCurrentOwnership() throws IOException {
		if (dataLocation != null) SharedObjectOwnership.publish(dataLocation, "server", currentOwnershipHashes());
	}

	private TreeSet<String> currentOwnershipHashes() throws IOException {
		TreeSet<String> hashes = new TreeSet<>();
		Optional<CurrentSnapshot> current = loadCurrentState(false, false);
		if (current.isEmpty()) return hashes;
		for (GenerationHistoryEntry entry : readCompactState(current.orElseThrow().record().metadata().generationId()).entries()) addExistingManifestHashes(entry.manifest(), hashes);
		return hashes;
	}

	private void addExistingManifestHashes(GroupManifest manifest, Set<String> hashes) {
		for (var group : manifest.groups().values()) for (var file : group.files().values()) {
			String hash = file.sha1().toLowerCase(Locale.ROOT);
			if (Files.isRegularFile(objectPathUnchecked(hash), LinkOption.NOFOLLOW_LINKS)) hashes.add(hash);
		}
	}

	private static void addManifestHashes(GroupManifest manifest, Set<String> hashes) {
		for (var group : manifest.groups().values()) for (var file : group.files().values()) hashes.add(file.sha1().toLowerCase(Locale.ROOT));
	}

	private PublicationGuard acquirePublicationGuard() throws IOException {
		PublicationLockRegistry.LockLease jvmLock = PUBLICATION_LOCKS.acquire(root);
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
			jvmLock.close();
			throw e;
		}
	}

	private Publication publication(PublicationStatus status, CurrentSnapshot snapshot) {
		return new Publication(status, snapshot.record(), snapshot.projectionPath(), snapshot.hostingPaths());
	}

	private void requireExpected(Optional<CurrentSnapshot> expected, Optional<CurrentSnapshot> actual) throws IOException {
		if (expected.isPresent() != actual.isPresent()) throw new IOException("Current generation changed before publication");
		if (expected.isPresent() && !expected.get().record().metadata().generationId().equals(actual.get().record().metadata().generationId()))
			throw new IOException("Current generation changed before publication");
	}

	private void requireCurrentStillMatches(Optional<CurrentSnapshot> expected) throws IOException {
		Optional<String> actualGenerationId = Files.exists(currentPath, LinkOption.NOFOLLOW_LINKS) ? Optional.of(readCurrentPointer().generationId) : Optional.empty();
		if (expected.isPresent() != actualGenerationId.isPresent()) throw new IOException("Current generation changed before publication");
		if (expected.isPresent() && !expected.orElseThrow().record().metadata().generationId().equals(actualGenerationId.orElseThrow()))
			throw new IOException("Current generation changed before publication");
	}

	private void createStoreDirectories() throws IOException {
		FileTrees.createManagedDirectory(root, "generation store");
		FileTrees.createManagedDirectory(cataloguesDirectory, "generation catalogues");
		FileTrees.createManagedDirectory(commitsDirectory, "generation commits");
		FileTrees.createManagedDirectory(deltasDirectory, "generation deltas");
		FileTrees.createManagedDirectory(objectsDirectory, "immutable objects");
		FileTrees.createManagedDirectory(stagingDirectory, "generation staging");
	}

	private CompactionPreview previewCompactionLocked(String boundaryGenerationId) throws IOException {
		GenerationHistoryIndex index = currentHistoryIndexLocked().orElseThrow(() -> new IOException("Cannot compact without a valid current generation"));
		GenerationHistoryIndex.Entry boundaryEntry = index.find(boundaryGenerationId).orElseThrow(() -> new IOException("Compaction boundary is not in the current generation lineage: " + boundaryGenerationId));
		if (!boundaryEntry.rollbackAvailable()) throw new IOException("Compaction boundary has already lost detailed rollback state: " + boundaryGenerationId);
		int boundary = index.entries().stream().map(GenerationHistoryIndex.Entry::generationId).toList().indexOf(boundaryGenerationId);
		NavigableSet<String> supersededGenerationIds = new TreeSet<>();
		for (int position = 0; position < boundary; position++) supersededGenerationIds.add(index.entries().get(position).generationId());
		Set<String> retainedStateDigests = new HashSet<>();
		for (int position = boundary; position < index.entries().size(); position++) retainedStateDigests.add(index.entries().get(position).stateDigest());
		NavigableSet<String> supersededCatalogueStateDigests = new TreeSet<>();
		for (int position = 0; position < boundary; position++) {
			String stateDigest = index.entries().get(position).stateDigest();
			if (!retainedStateDigests.contains(stateDigest)) supersededCatalogueStateDigests.add(stateDigest);
		}
		List<Path> cataloguePaths = supersededCatalogueStateDigests.stream().map(this::cataloguePathUnchecked).toList();
		List<Path> commitPaths = supersededGenerationIds.stream().map(this::commitPathUnchecked).toList();
		List<Path> deltaPaths = supersededGenerationIds.stream().map(this::deltaPathUnchecked).toList();
		validateDeletionTargets(cataloguePaths, "generation catalogue");
		validateDeletionTargets(commitPaths, "generation commit");
		validateDeletionTargets(deltaPaths, "generation ownership delta");
		return new CompactionPreview(boundaryGenerationId, List.copyOf(supersededGenerationIds), List.copyOf(supersededGenerationIds),
				List.copyOf(supersededCatalogueStateDigests), reclaimableBytes(cataloguePaths), reclaimableBytes(commitPaths), reclaimableBytes(deltaPaths));
	}

	private CompactionResult compactBeforeLocked(String boundaryGenerationId) throws IOException {
		GenerationCheckpoint pending = readCheckpoint().filter(checkpoint -> !checkpoint.supersededGenerationIds().isEmpty() || !checkpoint.supersededCatalogueStateDigests().isEmpty()).orElse(null);
		if (pending != null) {
			CompactionCleanup cleanup = finishCompactionLocked(pending);
			if (pending.boundaryGenerationId().equals(boundaryGenerationId))
				return new CompactionResult(boundaryGenerationId, List.copyOf(pending.supersededGenerationIds()), List.copyOf(pending.supersededCatalogueStateDigests()),
						cleanup.catalogues().count(), cleanup.commits().count(), cleanup.deltas().count(), cleanup.catalogues().bytes(), cleanup.commits().bytes(), cleanup.deltas().bytes());
		}
		Optional<CurrentSnapshot> current = loadCurrentState(true, false);
		if (current.isEmpty()) throw new IOException("Cannot compact without a valid current generation");
		GenerationRecord currentRecord = current.orElseThrow().record();
		GenerationHistoryIndex fullIndex = historyIndex(currentRecord.metadata().generationId());
		CompactionPreview preview = previewCompactionLocked(boundaryGenerationId);
		if (preview.supersededGenerationIds().isEmpty()) return new CompactionResult(boundaryGenerationId, preview.supersededGenerationIds(), preview.supersededCatalogueStateDigests(), 0, 0, 0, 0, 0, 0);
		CompactHistory history = readCompactHistory(currentRecord.metadata().generationId());
		GenerationHistoryEntry boundaryEntry = history.entries().stream().filter(entry -> entry.metadata().generationId().equals(boundaryGenerationId)).findFirst().orElse(null);
		if (boundaryEntry == null) throw new IOException("Compaction boundary details are no longer available: " + boundaryGenerationId);
		GenerationRecord boundaryRecord = readCompactState(boundaryGenerationId).record();
		int patchBoundary = history.patchNotesHistory().stream().map(GenerationPatchNoteHistory.Entry::generationId).toList().indexOf(boundaryGenerationId);
		if (patchBoundary < 0) throw new IOException("Compaction boundary patch-note entry is missing: " + boundaryGenerationId);
		List<GenerationPatchNoteHistory.Entry> retainedPatchNotes = history.patchNotesHistory().subList(0, patchBoundary + 1);
		GenerationHistoryIndex compactedIndex;
		try {
			compactedIndex = fullIndex.compactBefore(boundaryGenerationId);
		} catch (RuntimeException e) {
			throw new IOException("Generation history compaction boundary is invalid: " + boundaryGenerationId, e);
		}
		GenerationCheckpoint checkpoint = new GenerationCheckpoint(boundaryRecord, retainedPatchNotes, compactedIndex,
				new TreeSet<>(preview.supersededGenerationIds()), new TreeSet<>(preview.supersededCatalogueStateDigests()));
		ConfigTools.writeAtomic(checkpointPath, checkpoint.toFields());
		GenerationCheckpoint verifiedCheckpoint = readCheckpoint().orElseThrow(() -> new IOException("Generation checkpoint disappeared after publication"));
		if (!verifiedCheckpoint.equals(checkpoint) || !verifiedCheckpoint.record().equals(boundaryRecord))
			throw new IOException("Generation checkpoint does not match the retained compaction boundary");

		CompactionCleanup cleanup = finishCompactionLocked(checkpoint);
		return new CompactionResult(boundaryGenerationId, preview.supersededGenerationIds(), preview.supersededCatalogueStateDigests(), cleanup.catalogues().count(), cleanup.commits().count(),
				cleanup.deltas().count(), cleanup.catalogues().bytes(), cleanup.commits().bytes(), cleanup.deltas().bytes());
	}

	private void recoverCompactionLocked() throws IOException {
		GenerationCheckpoint checkpoint = readCheckpoint().orElse(null);
		if (checkpoint == null || checkpoint.supersededGenerationIds().isEmpty() && checkpoint.supersededCatalogueStateDigests().isEmpty()) return;
		finishCompactionLocked(checkpoint);
	}

	private CompactionCleanup finishCompactionLocked(GenerationCheckpoint checkpoint) throws IOException {
		GenerationJsons.GenerationPointerFields pointer = readCurrentPointer();
		GenerationRecord currentRecord = readCompactState(pointer.generationId).record();
		writeCurrentProjection(currentRecord);
		DeletionResult catalogues = deleteCompactionFiles(checkpoint.supersededCatalogueStateDigests().stream().map(this::cataloguePathUnchecked).toList(), "generation catalogue");
		DeletionResult commits = deleteCompactionFiles(checkpoint.supersededGenerationIds().stream().map(this::commitPathUnchecked).toList(), "generation commit");
		DeletionResult deltas = deleteCompactionFiles(checkpoint.supersededGenerationIds().stream().map(this::deltaPathUnchecked).toList(), "generation ownership delta");
		publishCurrentOwnership();
		GenerationCheckpoint completed = new GenerationCheckpoint(checkpoint.record(), checkpoint.patchNotesHistory(), checkpoint.historyIndex(), Set.of(), Set.of());
		ConfigTools.writeAtomic(checkpointPath, completed.toFields());
		return new CompactionCleanup(catalogues, commits, deltas);
	}

	private void validateDeletionTargets(List<Path> paths, String description) throws IOException {
		for (Path path : paths) if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) FileTrees.requireRegularFile(path, description);
	}

	private DeletionResult deleteCompactionFiles(List<Path> paths, String description) throws IOException {
		long deleted = 0;
		long bytes = 0;
		for (Path path : paths) if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			FileTrees.requireRegularFile(path, description);
			long size = Files.size(path);
			compactionDeleteHook.beforeDelete(path);
			if (ImmutableFiles.deleteIfExists(path)) {
				deleted = addExact(deleted, 1, "deleted " + description + " count");
				bytes = addExact(bytes, size, "deleted " + description + " bytes");
			}
		}
		if (deleted > 0 && !paths.isEmpty()) FileTrees.forceDirectory(paths.get(0).getParent());
		return new DeletionResult(deleted, bytes);
	}

	private long reclaimableBytes(List<Path> paths) throws IOException {
		long bytes = 0;
		for (Path path : paths) if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) bytes = addExact(bytes, Files.size(path), "compaction reclaimable bytes");
		return bytes;
	}

	private Path cataloguePathUnchecked(String stateDigest) {
		return cataloguesDirectory.resolve(stateDigest + ".json");
	}

	private Path commitPathUnchecked(String generationId) {
		return commitsDirectory.resolve(generationId + ".json");
	}

	private Path deltaPathUnchecked(String generationId) {
		return deltasDirectory.resolve(generationId + ".json");
	}

	private record DeletionResult(long count, long bytes) {}

	private StorageReport measureStorageLocked() throws IOException {
		GenerationRecord current = loadCurrentState(true, false).map(CurrentSnapshot::record).orElse(null);
		Map<String, Long> expectedSizes = new TreeMap<>();
		long objectReferences = 0;
		if (current != null) objectReferences = addExact(objectReferences, addManifestReferences(current, expectedSizes), "object reference count");
		long referencedObjectBytes = verifyObjectReferences(expectedSizes);
		FileTotals catalogueFiles = fileTotals(regularFiles(cataloguesDirectory, "generation catalogues"));
		FileTotals commitFiles = fileTotals(regularFiles(commitsDirectory, "generation commits"));
		FileTotals deltaFiles = fileTotals(regularFiles(deltasDirectory, "generation deltas"));
		FileTotals objectFiles = fileTotals(objectFiles());
		FileTotals stagingFiles = fileTotals(regularFiles(stagingDirectory, "generation staging"));
		return new StorageReport(catalogueFiles.count(), catalogueFiles.bytes(), commitFiles.count(), commitFiles.bytes(),
				deltaFiles.count(), deltaFiles.bytes(), objectFiles.count(), objectFiles.bytes(), stagingFiles.count(), stagingFiles.bytes(), expectedSizes.size(),
				referencedObjectBytes, objectReferences);
	}

	private CollectionResult collectUnreachableObjectsLocked(Set<String> generationPins, Set<String> objectPins) throws IOException {
		Optional<CurrentSnapshot> current = loadCurrentState(true, false);
		if (current.isEmpty()) throw new IOException("Cannot collect without a valid current generation");
		String currentGenerationId = current.orElseThrow().record().metadata().generationId();
		CompactHistory history = readCompactHistory(currentGenerationId);
		NavigableSet<String> retained = new TreeSet<>(generationPins);
		retained.add(currentGenerationId);
		Map<String, Long> expectedSizes = new TreeMap<>();
		OwnershipLedger.Builder ledger = history.boundaryRecord() == null
				? OwnershipLedger.builder(history.generations().get(0).commit().modpackId())
				: OwnershipLedger.builder(history.boundaryRecord().ownershipLedger());
		if (history.boundaryRecord() != null && retained.contains(history.boundaryRecord().metadata().generationId())) {
			addManifestReferences(history.boundaryRecord(), expectedSizes);
		}
		for (CompactGeneration generation : history.generations()) {
			try {
				ledger.apply(generation.delta(), generation.commit().generationId());
			} catch (RuntimeException e) {
				throw new IOException("Generation ownership delta cannot be applied: " + generation.commit().generationId(), e);
			}
			if (retained.contains(generation.commit().generationId())) {
				addManifestReferences(generation.snapshot().manifest(), expectedSizes);
			}
		}
		Set<String> availableGenerationIds = new HashSet<>();
		if (history.boundaryRecord() != null) availableGenerationIds.add(history.boundaryRecord().metadata().generationId());
		for (CompactGeneration generation : history.generations()) availableGenerationIds.add(generation.commit().generationId());
		for (String generationId : generationPins) {
			if (!availableGenerationIds.contains(generationId))
				throw new IOException("Retained generation is not in the current lineage: " + generationId);
		}
		verifyObjectReferences(expectedSizes);
		Set<String> reachable = new HashSet<>(expectedSizes.keySet());
		for (String objectHash : objectPins) {
			if (!reachable.contains(objectHash)) verifyPinnedObject(objectHash);
			reachable.add(objectHash);
		}
		if (dataLocation != null) return SharedObjectOwnership.withGlobalReferences(dataLocation, "server", reachable, this::deleteUnreachableObjects);
		return deleteUnreachableObjects(reachable);
	}

	private CollectionResult deleteUnreachableObjects(Set<String> reachable) throws IOException {
		List<Path> beforeFiles = objectFiles();
		FileTotals before = fileTotals(beforeFiles);
		long deletedCount = 0;
		long deletedBytes = 0;
		for (Path object : beforeFiles) {
			String hash = DataRootResolver.objectHash(objectsDirectory, object);
			if (hash == null || reachable.contains(hash) || !FileIntegrity.matchesCanonicalSha1(object, hash)) continue;
			long size;
			try {
				size = Files.size(object);
			} catch (NoSuchFileException e) {
				// Another collector removed the unreachable object between the listing and this stat.
				continue;
			}
			if (ImmutableFiles.deleteIfExists(object)) {
				deletedCount = addExact(deletedCount, 1, "deleted object count");
				deletedBytes = addExact(deletedBytes, size, "deleted object bytes");
			}
		}
		if (deletedCount > 0) FileTrees.forceDirectory(objectsDirectory);
		FileTotals after = fileTotals(objectFiles());
		return new CollectionResult(before.bytes(), after.bytes(), before.count(), after.count(), deletedCount, deletedBytes);
	}

	private List<Path> regularFiles(Path directory, String description) throws IOException {
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
		FileTrees.requireDirectory(directory, description);
		try (var paths = Files.list(directory)) {
			return paths.filter(path -> !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					.sorted(Comparator.comparing(value -> value.getFileName().toString())).toList();
		}
	}

	private FileTotals fileTotals(List<Path> paths) throws IOException {
		long count = 0;
		long bytes = 0;
		for (Path path : paths) {
			if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
			try {
				count = addExact(count, 1, "file count");
				bytes = addExact(bytes, Files.size(path), "file bytes");
			} catch (NoSuchFileException e) {
				// A concurrent collector may remove an unreachable object between the listing and this stat; the file is simply gone.
			}
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

	private static void addExpectedSize(Map<String, Long> expectedSizes, String sha1, long expectedSize) throws IOException {
		if (!isDigest(sha1) || expectedSize < 0) throw new IOException("Invalid immutable object reference: " + sha1);
		Long previousSize = expectedSizes.putIfAbsent(sha1, expectedSize);
		if (previousSize != null && previousSize.longValue() != expectedSize)
			throw new IOException("Immutable object has conflicting advertised sizes: " + sha1);
	}

	private long verifyObjectReferences(Map<String, Long> expectedSizes) throws IOException {
		try (FileMetadataCache cache = openMetadataCache()) {
			Set<String> verified = new HashSet<>();
			long bytes = 0;
			for (var entry : expectedSizes.entrySet()) {
				verifyObject(entry.getKey(), entry.getValue(), expectedSizes, verified, cache);
				bytes = addExact(bytes, entry.getValue(), "referenced object bytes");
			}
			return bytes;
		}
	}

	private void verifyPinnedObject(String sha1) throws IOException {
		Path object = objectPath(sha1);
		FileTrees.requireRegularFile(object, "pinned immutable object " + sha1);
		if (!FileIntegrity.matchesCanonicalSha1(object, sha1)) throw new IOException("Pinned immutable object failed SHA-1 verification: " + object);
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

	private GenerationJsons.GenerationPointerFields readCurrentPointer() throws IOException {
		FileTrees.requireRegularFile(currentPath, "current generation pointer");
		try {
			GenerationJsons.GenerationPointerFields pointer = ConfigTools.parse(Files.readString(currentPath, StandardCharsets.UTF_8), GenerationJsons.GenerationPointerFields.class);
			if (pointer.schemaVersion != CURRENT_POINTER_SCHEMA_VERSION || !isDigest(pointer.generationId))
				throw new IOException("Invalid current generation pointer metadata: " + currentPath);
			return pointer;
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Invalid current generation pointer: " + currentPath, e);
		}
	}

	private Optional<GenerationCheckpoint> readCheckpoint() throws IOException {
		if (!Files.exists(checkpointPath, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		FileTrees.requireRegularFile(checkpointPath, "generation history checkpoint");
		try {
			return Optional.of(GenerationCheckpoint.fromFields(ConfigTools.parse(Files.readString(checkpointPath, StandardCharsets.UTF_8), GenerationJsons.GenerationCheckpointFields.class)));
		} catch (RuntimeException e) {
			throw new IOException("Invalid generation history checkpoint: " + checkpointPath, e);
		}
	}

	private LoadedProjection readProjectionOrCompact(String generationId) throws IOException {
		IOException projectionFailure = null;
		if (Files.exists(currentProjectionPath, LinkOption.NOFOLLOW_LINKS)) {
			try {
				GenerationRecord projection = readProjection(currentProjectionPath);
				if (!projection.metadata().generationId().equals(generationId))
					throw new IOException("Current projection does not match the current generation identity: " + currentProjectionPath);
				Optional<GenerationHistoryIndex> index = readProjectionHistoryIndex(currentProjectionPath);
				return new LoadedProjection(projection, currentProjectionPath, index.orElse(null), index.isEmpty());
			} catch (IOException e) {
				projectionFailure = e;
			}
		}
		GenerationRecord record = readCompactRecord(generationId);
		if (projectionFailure != null)
			Constants.LOGGER.warn("Current generation projection is invalid; using durable generation state until it is repaired: {}", currentProjectionPath, projectionFailure);
		else
			Constants.LOGGER.debug("Current generation projection is missing; rebuilding it from compact metadata: {}", currentProjectionPath);
		return new LoadedProjection(record, currentProjectionPath, null, true);
	}

	private GenerationRecord readProjection(Path path) throws IOException {
		FileTrees.requireRegularFile(path, "current generation projection");
		try {
			ModpackJsons.CompleteModpackContentFields fields = ConfigTools.parse(Files.readString(path, StandardCharsets.UTF_8), ModpackJsons.CompleteModpackContentFields.class);
			GenerationPatchNoteHistory.fromFields(fields);
			return GenerationRecord.fromFields(fields);
		} catch (RuntimeException e) {
			throw new IOException("Invalid current generation projection: " + path, e);
		}
	}

	private Optional<GenerationHistoryIndex> readProjectionHistoryIndex(Path path) throws IOException {
		try {
			ModpackJsons.CompleteModpackContentFields fields = ConfigTools.parse(Files.readString(path, StandardCharsets.UTF_8), ModpackJsons.CompleteModpackContentFields.class);
			return fields.generationHistory == null ? Optional.empty() : Optional.of(GenerationHistoryIndex.fromFields(fields.generationHistory));
		} catch (RuntimeException e) {
			throw new IOException("Invalid current generation history index: " + path, e);
		}
	}

	private OwnershipDelta readDelta(String generationId) throws IOException {
		return readDelta(deltaPath(generationId));
	}

	private OwnershipDelta readDelta(Path path) throws IOException {
		FileTrees.requireRegularFile(path, "generation ownership delta");
		try {
			return OwnershipDelta.fromFields(ConfigTools.parse(Files.readString(path, StandardCharsets.UTF_8), GenerationJsons.OwnershipDeltaFields.class));
		} catch (RuntimeException e) {
			throw new IOException("Invalid generation ownership delta: " + path, e);
		}
	}

	private CatalogueSnapshot readCatalogue(String stateDigest) throws IOException {
		return readCatalogue(cataloguePath(stateDigest));
	}

	private CatalogueSnapshot readCatalogue(Path path) throws IOException {
		FileTrees.requireRegularFile(path, "generation catalogue snapshot");
		try {
			CatalogueSnapshot snapshot = CatalogueSnapshot.fromFields(ConfigTools.parse(Files.readString(path, StandardCharsets.UTF_8), GenerationJsons.CatalogueSnapshotFields.class));
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
		FileTrees.requireRegularFile(path, "generation commit");
		try {
			GenerationCommit commit = GenerationCommit.fromFields(ConfigTools.parse(Files.readString(path, StandardCharsets.UTF_8), GenerationJsons.GenerationCommitFields.class));
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
		FileTrees.requireDirectory(cataloguesDirectory, "generation catalogues");
		FileTrees.requireDirectory(commitsDirectory, "generation commits");
		FileTrees.requireDirectory(deltasDirectory, "generation deltas");
		GenerationCheckpoint checkpoint = readCheckpoint().orElse(null);
		Set<String> visited = new HashSet<>();
		List<CompactGeneration> reverse = new ArrayList<>();
		String currentId = generationId;
		boolean reachedCheckpoint = false;
		while (true) {
			if (checkpoint != null && currentId.equals(checkpoint.boundaryGenerationId())) {
				reachedCheckpoint = true;
				break;
			}
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
		if (checkpoint != null) {
			if (!reachedCheckpoint) throw new IOException("Generation history checkpoint is not an ancestor of the current generation: " + checkpoint.boundaryGenerationId());
			validateCheckpointBoundaryFiles(checkpoint);
		}
		Collections.reverse(reverse);
		List<GenerationHistoryEntry> entries = new ArrayList<>();
		if (checkpoint != null) {
			try {
				entries.add(new GenerationHistoryEntry(checkpoint.record().manifest(), checkpoint.record().metadata()));
			} catch (RuntimeException e) {
				throw new IOException("Generation history checkpoint does not form a valid history entry", e);
			}
		}
		if (checkpoint == null && reverse.isEmpty()) throw new IOException("Generation compact parent chain is empty");
		String expectedParent = checkpoint == null ? GenerationMetadata.ROOT_PARENT : checkpoint.boundaryGenerationId();
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
		List<GenerationPatchNoteHistory.Entry> patchNotesHistory;
		if (checkpoint == null) {
			patchNotesHistory = GenerationPatchNoteHistory.fromHistory(entries);
		} else {
			List<GenerationPatchNoteHistory.Entry> patchNotes = new ArrayList<>(checkpoint.patchNotesHistory());
			for (int index = 1; index < entries.size(); index++) patchNotes.add(GenerationPatchNoteHistory.Entry.fromMetadata(entries.get(index).metadata()));
			patchNotesHistory = GenerationPatchNoteHistory.validateForGeneration(patchNotes, generationId);
		}
		return new CompactHistory(checkpoint == null ? null : checkpoint.record(), List.copyOf(reverse), List.copyOf(entries), patchNotesHistory);
	}

	private CompactState readCompactState(String generationId) throws IOException {
		CompactHistory history = readCompactHistory(generationId);
		OwnershipLedger.Builder ledger = history.boundaryRecord() == null
				? OwnershipLedger.builder(history.generations().get(0).commit().modpackId())
				: OwnershipLedger.builder(history.boundaryRecord().ownershipLedger());
		GenerationRecord record = history.boundaryRecord();
		for (CompactGeneration generation : history.generations()) {
			try {
				ledger.apply(generation.delta(), generation.commit().generationId());
				OwnershipLedger materialized = ledger.build();
				record = new GenerationRecord(generation.snapshot().manifest(), generation.commit().metadata(), materialized);
				if (!GenerationCommit.from(record, generation.delta()).equals(generation.commit()))
					throw new IOException("Generation compact commit does not match reconstructed record: " + generation.commit().generationId());
			} catch (RuntimeException e) {
				throw new IOException("Generation ownership delta cannot be applied: " + generation.commit().generationId(), e);
			}
		}
		if (record == null) throw new IOException("Generation compact parent chain is empty");
		if (!record.metadata().generationId().equals(generationId)) throw new IOException("Current compact state identity does not match: " + generationId);
		return new CompactState(record, history.entries());
	}

	private void validateCheckpointBoundaryFiles(GenerationCheckpoint checkpoint) throws IOException {
		GenerationRecord record = checkpoint.record();
		Path commitFile = commitPath(checkpoint.boundaryGenerationId());
		if (Files.exists(commitFile, LinkOption.NOFOLLOW_LINKS)) {
			GenerationCommit commit = readCommit(commitFile);
			if (!commit.metadata().equals(record.metadata()) || !commit.modpackId().equals(record.manifest().modpackId()))
				throw new IOException("Generation checkpoint does not match its boundary commit: " + commitFile);
			Path catalogueFile = cataloguePath(record.metadata().stateDigest());
			if (Files.exists(catalogueFile, LinkOption.NOFOLLOW_LINKS) && !readCatalogue(catalogueFile).manifest().equals(record.manifest()))
				throw new IOException("Generation checkpoint does not match its boundary catalogue: " + catalogueFile);
			Path deltaFile = deltaPath(checkpoint.boundaryGenerationId());
			if (Files.exists(deltaFile, LinkOption.NOFOLLOW_LINKS)) {
				OwnershipDelta delta = readDelta(deltaFile);
				if (!delta.modpackId().equals(record.manifest().modpackId()) || !delta.digest().equals(commit.ownershipDeltaDigest()))
					throw new IOException("Generation checkpoint does not match its boundary ownership delta: " + deltaFile);
			}
		} else {
			Path catalogueFile = cataloguePath(record.metadata().stateDigest());
			if (Files.exists(catalogueFile, LinkOption.NOFOLLOW_LINKS) && !readCatalogue(catalogueFile).manifest().equals(record.manifest()))
				throw new IOException("Generation checkpoint does not match its boundary catalogue: " + catalogueFile);
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
		addManifestReferences(record, expectedSizes);
		verifyObjectReferences(expectedSizes);
	}

	private void verifyObject(String sha1, long expectedSize, Map<String, Long> expectedSizes, Set<String> verified, FileMetadataCache cache) throws IOException {
		Path object = objectPath(sha1);
		Long previousSize = expectedSizes.putIfAbsent(sha1, expectedSize);
		if (previousSize != null && previousSize.longValue() != expectedSize)
			throw new IOException("Immutable object has conflicting advertised sizes: " + sha1);
		if (!verified.add(sha1)) return;
		FileTrees.requireRegularFile(object, "immutable object " + sha1);
		if (!FileIntegrity.matchesNamed(object, expectedSize, sha1, cache)) throw new IOException("Immutable object failed size/SHA-1 verification: " + object);
	}

	private FileMetadataCache openMetadataCache() throws IOException {
		Path directory = dataLocation != null ? dataLocation.layout().fileMetadataDirectory() : objectsDirectory.toAbsolutePath().normalize().getParent().resolve("file-metadata");
		return FileMetadataCache.open(directory);
	}

	private Path objectPath(String sha1) throws IOException {
		if (!isDigest(sha1)) throw new IOException("Invalid immutable object SHA-1: " + sha1);
		try {
			return DataRootResolver.objectFile(objectsDirectory, sha1);
		} catch (IllegalArgumentException e) {
			throw new IOException("Object path escapes immutable object store: " + sha1, e);
		}
	}

	private Path objectPathUnchecked(String sha1) {
		return DataRootResolver.objectFile(objectsDirectory, sha1);
	}

	private List<Path> objectFiles() throws IOException {
		if (!Files.exists(objectsDirectory, LinkOption.NOFOLLOW_LINKS)) return List.of();
		FileTrees.requireDirectory(objectsDirectory, "immutable objects");
		try (var shards = Files.list(objectsDirectory)) {
			List<Path> result = new ArrayList<>();
			for (Path shard : shards.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
				if (Files.isSymbolicLink(shard)) throw new IOException("Immutable object store contains a symbolic link: " + shard);
				if (Files.isRegularFile(shard, LinkOption.NOFOLLOW_LINKS)) continue;
				if (!Files.isDirectory(shard, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Immutable object store contains an unsupported entry: " + shard);
				try (var files = Files.list(shard)) {
					for (Path file : files.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
						if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
							throw new IOException("Immutable object store contains an unsupported entry: " + file);
						if (DataRootResolver.isObjectFile(objectsDirectory, file)) result.add(file);
					}
				}
			}
			return List.copyOf(result);
		}
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
		if (filename.length() != HashUtils.SHA1_HEX_LENGTH + ".json".length() || !filename.endsWith(".json")) throw new IOException("Invalid generation catalogue path: " + path);
		String stateDigest = filename.substring(0, HashUtils.SHA1_HEX_LENGTH);
		if (!isDigest(stateDigest)) throw new IOException("Invalid generation catalogue filename: " + path);
		return stateDigest;
	}

	private String commitGenerationId(Path path) throws IOException {
		String filename = path.getFileName().toString();
		if (filename.length() != HashUtils.SHA1_HEX_LENGTH + ".json".length() || !filename.endsWith(".json")) throw new IOException("Invalid generation commit path: " + path);
		String generationId = filename.substring(0, HashUtils.SHA1_HEX_LENGTH);
		if (!isDigest(generationId)) throw new IOException("Invalid generation commit filename: " + path);
		return generationId;
	}

	private static GenerationJsons.GenerationPointerFields pointer(GenerationRecord record) {
		GenerationJsons.GenerationPointerFields pointer = new GenerationJsons.GenerationPointerFields();
		pointer.schemaVersion = CURRENT_POINTER_SCHEMA_VERSION;
		pointer.generationId = record.metadata().generationId();
		return pointer;
	}

	private void writeCurrentProjection(GenerationRecord record) throws IOException {
		ModpackJsons.CompleteModpackContentFields fields = record.toFields();
		CompactHistory history = readCompactHistory(record.metadata().generationId());
		List<GenerationPatchNoteHistory.Entry> patchNoteHistory = history.patchNotesHistory();
		GenerationPatchNoteHistory.writeFields(fields, patchNoteHistory);
		fields.generationHistory = historyIndex(history).toFields();
		ConfigTools.writeAtomic(currentProjectionPath, fields);
	}

	private OwnershipDelta writeDeltaNoClobber(GenerationRecord record, GenerationRecord parent) throws IOException {
		OwnershipLedger base = parent == null ? OwnershipLedger.empty(record.manifest().modpackId()) : parent.ownershipLedger();
		OwnershipDelta delta = OwnershipDelta.between(base, record.manifest());
		Path path = deltaPath(record.metadata().generationId());
		writeImmutableJsonNoClobber(path, deltasDirectory, delta.toFields(), delta, this::readDelta, "generation ownership delta");
		return delta;
	}

	private void writeCatalogueNoClobber(GenerationRecord record) throws IOException {
		CatalogueSnapshot snapshot = CatalogueSnapshot.from(record.manifest());
		Path path = cataloguePath(snapshot.stateDigest());
		writeImmutableJsonNoClobber(path, cataloguesDirectory, snapshot.toFields(), snapshot, this::readCatalogue, "generation catalogue snapshot");
	}

	private void writeCommitNoClobber(GenerationRecord record, OwnershipDelta delta) throws IOException {
		GenerationCommit commit = GenerationCommit.from(record, delta);
		Path path = commitPath(commit.generationId());
		writeImmutableJsonNoClobber(path, commitsDirectory, commit.toFields(), commit, this::readCommit, "generation commit");
	}

	private <T> void writeImmutableJsonNoClobber(Path path, Path directory, Object value, T expected,
			ImmutableJsonReader<T> reader, String description) throws IOException {
		FileTrees.createManagedDirectory(directory, description + "s");
		byte[] bytes = ConfigTools.GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
		ImmutableFilePublisher.publishBytes(path, bytes, existingPath -> {
			T existing = reader.read(existingPath);
			if (!existing.equals(expected)) throw new IOException(description + " already exists with different content: " + existingPath);
		});
	}

	private static boolean isDigest(String value) {
		return HashUtils.isCanonicalSha1(value);
	}

	private record CompactGeneration(GenerationCommit commit, CatalogueSnapshot snapshot, OwnershipDelta delta) {}

	private record CompactHistory(GenerationRecord boundaryRecord, List<CompactGeneration> generations, List<GenerationHistoryEntry> entries,
			List<GenerationPatchNoteHistory.Entry> patchNotesHistory) {}

	private record CompactionCleanup(DeletionResult catalogues, DeletionResult commits, DeletionResult deltas) {}

	private record CompactState(GenerationRecord record, List<GenerationHistoryEntry> entries) {}

	private record LoadedProjection(GenerationRecord record, Path path, GenerationHistoryIndex historyIndex, boolean needsRepair) {}

	private record FileTotals(long count, long bytes) {}

	private static final class PublicationGuard implements AutoCloseable {
		private final PublicationLockRegistry.LockLease jvmLock;
		private final FileChannel channel;
		private final FileLock fileLock;

		private PublicationGuard(PublicationLockRegistry.LockLease jvmLock, FileChannel channel, FileLock fileLock) {
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
					jvmLock.close();
				}
			}
		}
	}

}
