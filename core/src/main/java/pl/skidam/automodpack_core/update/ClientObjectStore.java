package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.storage.ObjectStoreMaintenance;
import pl.skidam.automodpack_core.storage.ObjectStoreMaintenance.ExpectedSizes;
import pl.skidam.automodpack_core.storage.SharedObjectOwnership;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/** Measures and explicitly maintains the client shared object store. */
public final class ClientObjectStore {

	private ClientObjectStore() {}

	/**
	 * Stores one immutable byte sequence in the client CAS under its content hash, keeping any already valid object.
	 * Used for the policy documents every fetched head carries: the mirror's entries name them, so offline generation
	 * reconstruction stays possible after records retired.
	 */
	public static void storeObject(ClientStorage storage, String sha1, byte[] bytes) throws IOException {
		String hash = HashUtils.normalizeSha1(sha1);
		if (!HashUtils.sha1(bytes).equals(hash)) throw new IOException("Object bytes do not match their content hash: " + hash);
		Path object = storage.objectFile(hash);
		if (FileIntegrity.matches(object, bytes.length, hash)) return;
		Path temporary = Files.createTempFile(storage.incomingDirectory(), ".object-", ".tmp");
		try {
			Files.write(temporary, bytes);
			Files.createDirectories(object.getParent());
			Files.move(temporary, object, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temporary);
		}
		if (!FileIntegrity.matches(object, bytes.length, hash)) throw new IOException("Stored client object failed verification: " + hash);
	}

	/** A deterministic receipt for client CAS and adjacent durable state. */
	public record StorageReport(
			long objectCount,
			long objectBytes,
			long referencedObjectCount,
			long referencedObjectBytes,
			long validReferencedObjectCount,
			long validReferencedObjectBytes,
			long missingReferencedObjectCount,
			long invalidReferencedObjectCount,
			long activeFileCount,
			long activeBytes,
			long metadataFileCount,
			long metadataBytes,
			long overlayFileCount,
			long overlayBytes,
			long baselineFileCount,
			long baselineBytes,
			long preservationFileCount,
			long preservationBytes,
			long incomingFileCount,
			long incomingBytes,
			long backupFileCount,
			long backupBytes) {
		public StorageReport {
			if (List.of(objectCount, objectBytes, referencedObjectCount, referencedObjectBytes, validReferencedObjectCount, validReferencedObjectBytes,
					missingReferencedObjectCount, invalidReferencedObjectCount, metadataFileCount, metadataBytes,
					overlayFileCount, overlayBytes, baselineFileCount, baselineBytes, preservationFileCount, preservationBytes,
					incomingFileCount, incomingBytes, backupFileCount, backupBytes).stream().anyMatch(value -> value < 0))
				throw new IllegalArgumentException("Client storage report values cannot be negative");
			if (validReferencedObjectCount > referencedObjectCount || missingReferencedObjectCount > referencedObjectCount
					|| invalidReferencedObjectCount > referencedObjectCount - missingReferencedObjectCount)
				throw new IllegalArgumentException("Client storage reference counts are inconsistent");
		}

		public OptionalDouble referencedObjectCoverageRatio() {
			return referencedObjectCount == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) validReferencedObjectCount / referencedObjectCount);
		}
	}

	/** The receipt returned by one explicitly requested collection pass. */
	public record CollectionResult(StorageReport before, StorageReport after, long deletedObjectCount, long deletedObjectBytes) {
		public CollectionResult {
			before = Objects.requireNonNull(before, "before receipt");
			after = Objects.requireNonNull(after, "after receipt");
			if (deletedObjectCount < 0 || deletedObjectBytes < 0) throw new IllegalArgumentException("Deleted object values cannot be negative");
			if (after.objectCount() > before.objectCount() || after.objectBytes() > before.objectBytes())
				throw new IllegalArgumentException("Collection increased the measured object store");
		}
	}

	/** Measures all client state without deleting or writing anything. */
	public static StorageReport measure(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		return measure(storage, collectReferences(storage));
	}

	/** Publishes a conservative durable receipt before or after client state changes. */
	public static void publishOwnership(ClientStorage storage) throws IOException {
		publishOwnership(storage, Set.of());
	}

	/** Publishes durable state plus temporary objects that an in-flight operation is acquiring. */
	public static void publishOwnership(ClientStorage storage, Set<String> temporaryObjectHashes) throws IOException {
		Objects.requireNonNull(storage, "storage");
		Objects.requireNonNull(temporaryObjectHashes, "temporary object hashes");
		ExpectedSizes references = collectReferences(storage);
		for (String hash : canonicalPins(temporaryObjectHashes, "temporary object")) references.optional(hash, -1, "in-flight acquisition");
		SharedObjectOwnership.publish(storage.dataLocation(), "client", references.hashes());
	}

	/** Returns every CAS hash referenced by validated client state, excluding historical ownership metadata. */
	public static Set<String> referencedHashes(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		return collectReferences(storage).hashes();
	}

	/** Returns only referenced hashes whose verified object is physically present in this client CAS. */
	public static Set<String> existingReferencedHashes(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		ExpectedSizes references = collectReferences(storage);
		TreeSet<String> existing = new TreeSet<>();
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			for (var entry : references.sizes().entrySet()) {
				Path object = storage.objectFile(entry.getKey());
				if (!Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)) continue;
				long size = entry.getValue() >= 0 ? entry.getValue() : Files.size(object);
				if (FileIntegrity.matchesNamed(object, size, entry.getKey(), cache)) existing.add(entry.getKey());
			}
		}
		return Set.copyOf(existing);
	}

	/** Validates all durable client state and all required CAS references without mutating storage. */
	public static StorageReport validate(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		return measure(storage, collectReferences(storage), true);
	}

	/**
	 * Explicitly collects canonical, valid CAS objects that no journal mirror entry, per-pack durable state, or
	 * pending transaction references. Decision 10 of the detached-history spec: every byte the mirror can reach stays;
	 * trimming history bytes is the separate manual compaction, so only genuinely orphaned objects are deleted here.
	 * Shared collection is serialized with durable receipts from every known game instance.
	 */
	public static CollectionResult collectUnreachableObjects(ClientStorage storage, Set<String> pinnedObjectHashes) throws IOException {
		Objects.requireNonNull(storage, "storage");
		Objects.requireNonNull(pinnedObjectHashes, "pinnedObjectHashes");
		ExpectedSizes references = collectReferences(storage);
		for (String hash : canonicalPins(pinnedObjectHashes, "pinned object")) references.optional(hash, -1, "explicit pin");
		return collectUnreachableObjects(storage, references);
	}

	/** Runs one deletion pass against an explicit reference set; the manual compaction supplies its reduced keep set here. */
	static CollectionResult collectUnreachableObjects(ClientStorage storage, ExpectedSizes references) throws IOException {
		return SharedObjectOwnership.withGlobalReferences(storage.dataLocation(), "client", references.hashes(), globallyReferenced -> {
			StorageReport before = measure(storage, references, true);
			ObjectStoreMaintenance.DeletionReceipt deletion = ObjectStoreMaintenance.deleteUnreachable(storage.objectsDirectory(), globallyReferenced);
			StorageReport after = measure(storage, references, true);
			return new CollectionResult(before, after, deletion.deletedCount(), deletion.deletedBytes());
		});
	}

	private static StorageReport measure(ClientStorage storage, ExpectedSizes references) throws IOException {
		return measure(storage, references, false);
	}

	private static StorageReport measure(ClientStorage storage, ExpectedSizes references, boolean requireRequiredReferences) throws IOException {
		ObjectStoreMaintenance.FileTotals objects = ObjectStoreMaintenance.fileTotals(ObjectStoreMaintenance.objectFiles(storage.objectsDirectory()));
		ReferenceTotals referenceTotals = measureReferences(storage, references, requireRequiredReferences);
		ObjectStoreMaintenance.FileTotals active = fileTotals(regularFiles(storage.activeDirectory(), "client active projection"));
		ObjectStoreMaintenance.FileTotals metadata = metadataTotals(storage);
		ObjectStoreMaintenance.FileTotals overlays = fileTotals(regularFiles(storage.overlaysDirectory(), "client overlays"));
		ObjectStoreMaintenance.FileTotals baselines = fileTotals(regularFiles(storage.baselinesDirectory(), "client baselines"));
		ObjectStoreMaintenance.FileTotals preservation = fileTotals(regularFiles(storage.preservationDirectory(), "client preservation vault"));
		ObjectStoreMaintenance.FileTotals incoming = fileTotals(regularFiles(storage.incomingDirectory(), "client incoming staging"));
		ObjectStoreMaintenance.FileTotals backup = fileTotals(regularFiles(storage.backupDirectory(), "client projection backups"));
		return new StorageReport(objects.count(), objects.bytes(), references.hashes().size(), referenceTotals.expectedBytes(), referenceTotals.validCount(), referenceTotals.validBytes(),
				referenceTotals.missingCount(), referenceTotals.invalidCount(), active.count(), active.bytes(), metadata.count(), metadata.bytes(), overlays.count(), overlays.bytes(),
				baselines.count(), baselines.bytes(), preservation.count(), preservation.bytes(), incoming.count(), incoming.bytes(), backup.count(), backup.bytes());
	}

	private static ExpectedSizes collectReferences(ClientStorage storage) throws IOException {
		ExpectedSizes retained = new ExpectedSizes();
		// The journal mirror is the client's only history store, so every hash any of its entries names is kept:
		// each entry's policy document, every change target, and every replaced source. The active generation's
		// tree is the set of change targets up to its entry, so the mirror sweep covers it as well.
		for (String modpackId : new ClientGenerationStore(storage).installedPackIds()) {
			for (JournalEntry entry : new JournalMirror(storage).entries(modpackId)) {
				retained.optional(entry.policySha1(), -1, "journal policy document");
				for (JournalEntry.Change change : entry.changes()) {
					if (change.toSha1() != null) retained.optional(change.toSha1(), change.toSize(), "journal change target");
					if (change.fromSha1() != null) retained.optional(change.fromSha1(), -1, "journal change source");
				}
			}
		}
		collectNonHistoryReferences(storage, retained);
		return retained;
	}

	/** Adds every durable client pin outside the mirror's history: overlays, baselines, generated copies, preservation, the pending transaction, and repair state. */
	static void collectNonHistoryReferences(ClientStorage storage, ExpectedSizes retained) throws IOException {
		collectBaselines(storage, retained);
		collectOverlays(storage, retained);
		collectGeneratedCopies(storage, retained);
		collectPreservation(storage, retained);
		collectTransaction(storage, retained);
		collectRepair(storage, retained);
		validateActiveProjection(storage);
	}

	private static void collectBaselines(ClientStorage storage, ExpectedSizes retained) throws IOException {
		for (Path modpack : childDirectories(storage.baselinesDirectory(), "client baselines")) {
			String modpackId = modpack.getFileName().toString();
			requireModpackId(modpackId, "client baseline directory");
			Path baseline = modpack.resolve("baseline.json");
			if (!Files.exists(baseline, LinkOption.NOFOLLOW_LINKS)) continue;
			FileTrees.requireRegularFile(baseline, "client baseline");
			ClientStorageJsons.ClientBaselineFields fields = readJson(baseline, ClientStorageJsons.ClientBaselineFields.class, "client baseline");
			if (fields.schemaVersion != 1 || !modpackId.equals(fields.modpackId) || fields.entries == null) throw new IOException("Client baseline identity is invalid: " + baseline);
			for (var entry : fields.entries) {
				if (entry == null || entry.logicalPath == null || entry.objectHash == null) throw new IOException("Client baseline entry is incomplete: " + baseline);
				if (entry.absent) {
					if (!entry.objectHash.isEmpty() || entry.size != -1) throw new IOException("Absent client baseline entry has content: " + baseline);
				} else {
					if (entry.size < 0) throw new IOException("Client baseline entry size is invalid: " + baseline);
					retained.require(entry.objectHash, entry.size, "client baseline");
				}
			}
		}
	}

	private static void collectOverlays(ClientStorage storage, ExpectedSizes retained) throws IOException {
		try (FileCache metadata = FileCache.open(storage.fileCacheDirectory())) {
			for (Path modpack : childDirectories(storage.overlaysDirectory(), "client overlays")) {
				String modpackId = modpack.getFileName().toString();
				requireModpackId(modpackId, "client overlay directory");
				try (Stream<Path> files = Files.walk(modpack)) {
					for (Path file : files.filter(path -> !path.equals(modpack)).sorted().toList()) {
						FileTrees.requireNoSymbolicLink(file, "client overlay");
						if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) retained.optional(metadata.getOrComputeHash(file), Files.size(file), "client overlay");
					}
				}
			}
		}
	}

	private static void collectGeneratedCopies(ClientStorage storage, ExpectedSizes retained) throws IOException {
		for (Path path : generatedCopyFiles(storage)) {
			Path generationDirectory = path.getParent();
			Path packDirectory = generationDirectory.getParent();
			String modpackId = packDirectory.getFileName().toString();
			String contentToken = generationDirectory.getFileName().toString();
			String selectionDigest = path.getFileName().toString().substring(0, HashUtils.SHA1_HEX_LENGTH);
			GeneratedCopyState state = GeneratedCopyState.read(storage, modpackId, contentToken, selectionDigest);
			for (GeneratedCopyState.Entry entry : state.entries()) retained.optional(entry.sha1(), entry.size(), "generated-copy state");
		}
	}

	private static void collectPreservation(ClientStorage storage, ExpectedSizes retained) throws IOException {
		for (Path modpack : childDirectories(storage.preservationDirectory(), "client preservation vault")) {
			String modpackId = requireModpackId(modpack.getFileName().toString(), "client preservation directory");
			PreservationVault.Snapshot snapshot = PreservationVault.read(storage, modpackId);
			for (PreservationVault.Claim claim : snapshot.claims()) retained.require(claim.objectHash(), claim.size(), "preservation claim");
		}
	}

	private static void collectTransaction(ClientStorage storage, ExpectedSizes retained) throws IOException {
		Path transactionPath = storage.transactionFile();
		if (!Files.exists(transactionPath, LinkOption.NOFOLLOW_LINKS)) return;
		FileTrees.requireRegularFile(transactionPath, "client transaction");
		UpdateTransaction transaction = readJson(transactionPath, UpdateTransaction.class, "client transaction");
		if (transaction.schemaVersion != UpdateTransaction.CURRENT_SCHEMA_VERSION || transaction.operations == null || transaction.projectedFinalState == null
				|| transaction.plannedPreservations == null || transaction.plannedBaselineCaptures == null || transaction.plannedConflicts == null)
			throw new IOException("Client transaction fields are incomplete: " + transactionPath);
		for (UpdatePlan.Operation operation : transaction.operations) {
			if (operation == null) throw new IOException("Client transaction contains an incomplete operation");
			retained.ifPresent(operation.expectedObjectHash(), operation.expectedSize(), "in-flight transaction operation");
			retained.ifPresent(operation.expectedExistingHash(), -1, "in-flight transaction source");
		}
		for (UpdatePlan.ProjectedFile projected : transaction.projectedFinalState) {
			if (projected == null) throw new IOException("Client transaction contains an incomplete projection");
			if (projected.present()) retained.require(projected.expectedHash(), projected.expectedSize(), "in-flight transaction projection");
		}
		for (UpdatePlan.BaselineCapture capture : transaction.plannedBaselineCaptures) {
			if (capture == null) throw new IOException("Client transaction contains an incomplete baseline capture");
			if (!capture.absent()) retained.require(capture.expectedHash(), capture.expectedSize(), "in-flight transaction baseline");
		}
		for (UpdatePlan.Preservation preservation : transaction.plannedPreservations) {
			if (preservation == null) throw new IOException("Client transaction contains an incomplete preservation");
			retained.require(preservation.expectedHash(), preservation.expectedSize(), "in-flight transaction preservation");
		}
		for (UpdatePlan.Conflict conflict : transaction.plannedConflicts) {
			if (conflict == null) throw new IOException("Client transaction contains an incomplete conflict");
			retained.optional(conflict.sourceHash(), conflict.sourceSize(), "in-flight transaction conflict source");
			retained.require(conflict.targetHash(), conflict.targetSize(), "in-flight transaction conflict target");
		}
	}

	private static void collectRepair(ClientStorage storage, ExpectedSizes retained) throws IOException {
		Path path = storage.repairJournalFile();
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
		FileTrees.requireRegularFile(path, "offline repair journal");
		ClientStorageJsons.OfflineRepairJournalFields fields = readJson(path, ClientStorageJsons.OfflineRepairJournalFields.class, "offline repair journal");
		if (fields.schemaVersion != 1 || fields.editableResets == null || fields.unownedMods == null) throw new IOException("Offline repair journal fields are incomplete: " + path);
		for (var reset : fields.editableResets) {
			if (reset == null) throw new IOException("Offline repair journal contains an incomplete editable reset");
			retained.require(reset.defaultHash, reset.defaultSize, "offline repair editable default");
			retained.ifPresent(reset.currentHash, reset.currentSize, "offline repair editable source");
		}
		for (var mod : fields.unownedMods) {
			if (mod == null) throw new IOException("Offline repair journal contains an incomplete unowned mod");
			retained.ifPresent(mod.objectHash, mod.size, "offline repair unowned mod");
		}
	}

	private static void validateActiveProjection(ClientStorage storage) throws IOException {
		if (Files.exists(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Client active projection is not a directory");
	}

	private static ReferenceTotals measureReferences(ClientStorage storage, ExpectedSizes references, boolean requireRequiredReferences) throws IOException {
		long expectedBytes = 0;
		long validCount = 0;
		long validBytes = 0;
		long missingCount = 0;
		long invalidCount = 0;
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			for (var entry : references.sizes().entrySet()) {
				String hash = entry.getKey();
				long expectedSize = entry.getValue();
				if (expectedSize >= 0) expectedBytes = ObjectStoreMaintenance.addExact(expectedBytes, expectedSize, "referenced object bytes");
				Path object = storage.objectFile(hash);
				if (Files.isSymbolicLink(object) || !Files.exists(object, LinkOption.NOFOLLOW_LINKS)) {
					if (requireRequiredReferences && references.required().contains(hash)) throw new IOException("Required client object is missing: " + hash);
					missingCount = ObjectStoreMaintenance.addExact(missingCount, 1, "missing referenced object count");
					continue;
				}
				long size = expectedSize >= 0 ? expectedSize : Files.size(object);
				boolean valid = FileIntegrity.matchesNamed(object, size, hash, cache);
				if (!valid) {
					if (requireRequiredReferences && references.required().contains(hash)) throw new IOException("Required client object is corrupt: " + hash);
					invalidCount = ObjectStoreMaintenance.addExact(invalidCount, 1, "invalid referenced object count");
					continue;
				}
				validCount = ObjectStoreMaintenance.addExact(validCount, 1, "valid referenced object count");
				validBytes = ObjectStoreMaintenance.addExact(validBytes, Files.size(object), "valid referenced object bytes");
			}
		}
		return new ReferenceTotals(expectedBytes, validCount, validBytes, missingCount, invalidCount);
	}

	private static ObjectStoreMaintenance.FileTotals metadataTotals(ClientStorage storage) throws IOException {
		ObjectStoreMaintenance.FileTotals total = fileTotals(regularFiles(storage.fileCacheDirectory(), "client file cache"));
		total = total.plus(fileTotals(regularFiles(storage.modCacheDirectory(), "client mod metadata")));
		total = total.plus(fileTotals(regularFiles(storage.packsDirectory(), "client pack metadata")));
		for (Path file : List.of(storage.stateFile(), storage.selectionFile(), storage.clientConfigFile(), storage.restartLoopStateFile(), storage.modpackContentTempFile()))
			if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) total = total.plus(fileTotals(List.of(FileTrees.requireRegularFile(file, "client metadata"))));
		return total;
	}

	private static List<Path> childDirectories(Path root, String description) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
		FileTrees.requireDirectory(root, description);
		try (Stream<Path> paths = Files.list(root)) {
			List<Path> result = new ArrayList<>();
			for (Path path : paths.sorted().toList()) {
				FileTrees.requireNoSymbolicLink(path, description);
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) result.add(path);
				else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException(description + " contains an unsupported entry: " + path);
			}
			return result;
		}
	}

	private static List<Path> regularFiles(Path directory, String description) throws IOException {
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
		FileTrees.requireDirectory(directory, description);
		try (Stream<Path> paths = Files.walk(directory)) {
			List<Path> result = new ArrayList<>();
			for (Path path : paths.filter(candidate -> !candidate.equals(directory)).sorted().toList()) {
				FileTrees.requireNoSymbolicLink(path, description);
				if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) result.add(path);
			}
			return List.copyOf(result);
		}
	}

	private static List<Path> generatedCopyFiles(ClientStorage storage) throws IOException {
		Path root = storage.generatedCopiesDirectory();
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
		FileTrees.requireDirectory(root, "client generated-copy state");
		List<Path> result = new ArrayList<>();
		try (Stream<Path> packs = Files.list(root)) {
			for (Path pack : packs.sorted().toList()) {
				FileTrees.requireNoSymbolicLink(pack, "client generated-copy state");
				if (!Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generated-copy state contains an unsupported entry: " + pack);
				requireModpackId(pack.getFileName().toString(), "client generated-copy directory");
				try (Stream<Path> generations = Files.list(pack)) {
					for (Path generation : generations.sorted().toList()) {
						FileTrees.requireNoSymbolicLink(generation, "client generated-copy state");
						if (!Files.isDirectory(generation, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generated-copy state contains an unsupported entry: " + generation);
						String contentToken = generation.getFileName().toString();
						if (!HashUtils.isCanonicalSha1(contentToken))
							throw new IOException("Client generated-copy directory is not canonical: " + contentToken);
						try (Stream<Path> states = Files.list(generation)) {
							for (Path state : states.sorted().toList()) {
								FileTrees.requireNoSymbolicLink(state, "client generated-copy state");
								String name = state.getFileName().toString();
								if (!Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS) || name.length() != HashUtils.SHA1_HEX_LENGTH + ".json".length() || !name.endsWith(".json")
										|| !HashUtils.isCanonicalSha1(name.substring(0, HashUtils.SHA1_HEX_LENGTH)))
									throw new IOException("Client generated-copy state contains an unsupported entry: " + state);
								result.add(state);
							}
						}
					}
				}
			}
		}
		return List.copyOf(result);
	}

	private static ObjectStoreMaintenance.FileTotals fileTotals(List<Path> paths) throws IOException {
		return ObjectStoreMaintenance.fileTotals(paths);
	}

	private static <T> T readJson(Path path, Class<T> type, String description) throws IOException {
		try {
			return ConfigTools.read(path, type).orElseThrow(() -> new IOException(description + " is empty: " + path));
		} catch (RuntimeException e) {
			throw new IOException(description + " is invalid: " + path, e);
		}
	}

	private static String requireModpackId(String value, String description) throws IOException {
		try {
			return ModpackId.requireValid(value);
		} catch (RuntimeException e) {
			throw new IOException("Invalid " + description + ": " + value, e);
		}
	}

	private static Set<String> canonicalPins(Set<String> pins, String description) throws IOException {
		return ObjectStoreMaintenance.canonicalPins(pins, description);
	}

	public static String normalizeHash(String sha1) {
		if (!HashUtils.isSha1(sha1)) throw new IllegalArgumentException("Invalid client object SHA-1");
		return HashUtils.normalizeSha1(sha1);
	}

	private record ReferenceTotals(long expectedBytes, long validCount, long validBytes, long missingCount, long invalidCount) {}

}
