package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.storage.ObjectStoreMaintenance;
import pl.skidam.automodpack_core.storage.ObjectStoreMaintenance.ExpectedSizes;
import pl.skidam.automodpack_core.storage.SharedObjectOwnership;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/** Measures and explicitly maintains the client shared object store. */
public final class ClientObjectStore {

	private ClientObjectStore() {}

	/** How a store object that fails its named tripwire is handled before acquisition populates it. */
	public enum CorruptObjectPolicy {
		/** Evicts the corrupt object quietly and lets a failed eviction propagate; the caller immediately reacquires the bytes itself. */
		EVICT_QUIETLY,
		/** Evicts the corrupt object with the standard warning and reports a failed eviction in the acquisition result. */
		EVICT_AND_REPORT,
		/** Keeps a corrupt object in place; the verified copy replaces it atomically. */
		KEEP
	}

	/** The outcome of one verified acquisition, carrying the failed eviction when the caller reports it. */
	public record Acquisition(Outcome outcome, IOException evictionFailure) {
		public enum Outcome {
			PRESENT, COPIED, MISSING_SOURCE, EVICTION_FAILED
		}

		/** Whether the store already held the verified object before this acquisition ran. */
		public boolean present() {
			return outcome == Outcome.PRESENT;
		}

		/** Whether the store does not hold the verified object after this acquisition ran. */
		public boolean missing() {
			return outcome != Outcome.PRESENT && outcome != Outcome.COPIED;
		}
	}

	/**
	 * The one verified acquisition into the client CAS: keeps a store object that already passes its named tripwire,
	 * otherwise handles a corrupt object per {@code corruptObjectPolicy} and copies the first candidate answering to
	 * the same named identity into the store. A failed eviction surfaces as {@link Acquisition.Outcome#EVICTION_FAILED}
	 * with its cause, or as {@code IOException} under {@link CorruptObjectPolicy#EVICT_QUIETLY} for callers that
	 * reacquire the bytes themselves.
	 */
	public static Acquisition acquireVerified(Path storeObject, String sha1, long size, List<Path> candidates, FileCache cache, CorruptObjectPolicy corruptObjectPolicy) throws IOException {
		if (FileIntegrity.matchesNamed(storeObject, size, sha1, cache)) return new Acquisition(Acquisition.Outcome.PRESENT, null);
		IOException evictionFailure = null;
		if (corruptObjectPolicy != CorruptObjectPolicy.KEEP && Files.exists(storeObject)) {
			if (corruptObjectPolicy == CorruptObjectPolicy.EVICT_AND_REPORT) LOGGER.warn("Evicting corrupt store object {}", sha1);
			try {
				ImmutableFiles.deleteIfExists(storeObject);
			} catch (IOException e) {
				if (corruptObjectPolicy == CorruptObjectPolicy.EVICT_QUIETLY) throw e;
				evictionFailure = e;
			}
		}
		if (evictionFailure != null) return new Acquisition(Acquisition.Outcome.EVICTION_FAILED, evictionFailure);
		for (Path candidate : candidates) {
			if (!FileIntegrity.matchesObject(candidate, storeObject, size, sha1, cache)) continue;
			VerifiedFileTransfer.copyAtomicImmutable(candidate, storeObject, size, sha1, cache);
			return new Acquisition(Acquisition.Outcome.COPIED, null);
		}
		return new Acquisition(Acquisition.Outcome.MISSING_SOURCE, null);
	}

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
		DurableFiles.writeAtomic(object, bytes);
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
			long incomingFileCount,
			long incomingBytes,
			long backupFileCount,
			long backupBytes) {
		public StorageReport {
			if (List.of(objectCount, objectBytes, referencedObjectCount, referencedObjectBytes, validReferencedObjectCount, validReferencedObjectBytes, missingReferencedObjectCount, invalidReferencedObjectCount,
					metadataFileCount, metadataBytes, overlayFileCount, overlayBytes, incomingFileCount, incomingBytes, backupFileCount,
					backupBytes).stream().anyMatch(value -> value < 0))
				throw new IllegalArgumentException("Client storage report values cannot be negative");
			if (validReferencedObjectCount > referencedObjectCount || missingReferencedObjectCount > referencedObjectCount || invalidReferencedObjectCount > referencedObjectCount - missingReferencedObjectCount)
				throw new IllegalArgumentException("Client storage reference counts are inconsistent");
		}

		public OptionalDouble referencedObjectCoverageRatio() {
			return referencedObjectCount == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) validReferencedObjectCount / referencedObjectCount);
		}
	}

	/** The receipt returned by one explicitly requested collection pass. */
	public record CollectionResult(StorageReport before, StorageReport after, long deletedObjectCount,
			long deletedObjectBytes, long deletedStagingCount, long deletedStagingBytes) {
		public CollectionResult {
			before = Objects.requireNonNull(before, "before receipt");
			after = Objects.requireNonNull(after, "after receipt");
			if (deletedObjectCount < 0 || deletedObjectBytes < 0 || deletedStagingCount < 0 || deletedStagingBytes < 0)
				throw new IllegalArgumentException("Deleted object values cannot be negative");
			if (after.objectCount() > before.objectCount() || after.objectBytes() > before.objectBytes()) throw new IllegalArgumentException("Collection increased the measured object store");
		}
	}

	/** Measures all client state, touching no valid state; only unusable durable state is set aside by the read policy. */
	public static StorageReport measure(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		return measure(storage, collectReferences(storage));
	}

	/**
	 * Publishes a conservative durable receipt before or after client state changes. One call sweeps every
	 * journal-mirror entry, overlay, generated copy, instance timeline tree, pending transaction, and repair state.
	 * Measured ~100ms warm for a 200-generation mirror plus a 200-snapshot timeline of a 50-file pack
	 * (649 hashes; {@code ClientObjectStoreTest.referenceSweepStaysCheapOnATwoHundredEntryJournal}). Callers
	 * publish at phase transitions, not per file. Timeline pins grow until the player uses Forget older than
	 * this; there is no automatic prune.
	 */
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

	/** Validates all durable client state and all required CAS references, leaving valid state untouched. */
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
			ObjectStoreMaintenance.DeletionReceipt staging = PartialResume.wipeSliceDirectories(storage.stagingDirectory());
			StorageReport after = measure(storage, references, true);
			return new CollectionResult(before, after, deletion.deletedCount(), deletion.deletedBytes(), staging.deletedCount(), staging.deletedBytes());
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
		ObjectStoreMaintenance.FileTotals incoming = fileTotals(regularFiles(storage.incomingDirectory(), "client incoming projection"));
		ObjectStoreMaintenance.FileTotals backup = fileTotals(regularFiles(storage.backupDirectory(), "client projection backups"));
		return new StorageReport(objects.count(), objects.bytes(), references.hashes().size(), referenceTotals.expectedBytes(), referenceTotals.validCount(), referenceTotals.validBytes(),
				referenceTotals.missingCount(), referenceTotals.invalidCount(), active.count(), active.bytes(), metadata.count(), metadata.bytes(), overlays.count(), overlays.bytes(),
				incoming.count(), incoming.bytes(), backup.count(), backup.bytes());
	}

	private static ExpectedSizes collectReferences(ClientStorage storage) throws IOException {
		// Client-owned receipts first: the sweep's strict half, where a size conflict is local corruption and throws.
		ExpectedSizes retained = new ExpectedSizes();
		collectNonHistoryReferences(storage, retained);
		mergeHistoryClaims(retained, collectMirrorClaims(storage));
		return retained;
	}

	/**
	 * The journal mirror is the client's only history store, so every hash any entry names stays reachable: each
	 * entry's policy document, every change target, and every replaced source. The active generation's tree is the
	 * set of change targets up to its entry, so the mirror covers it as well. The sweep is consent-free on purpose:
	 * every existing mirror keeps its bytes, and only the manual compaction drops a never-consented mirror whole, so
	 * a collection outside it can never leave a mirror dangling. The claims are server-authored history, so they are
	 * folded forgivingly: a buggy or hostile server must be able to fail a parse (the mirror asides unusable content)
	 * but never the sweep.
	 */
	static Map<String, Long> collectMirrorClaims(ClientStorage storage) throws IOException {
		TreeMap<String, Long> claims = new TreeMap<>();
		Set<String> distrusted = new HashSet<>();
		for (String modpackId : new ClientGenerationStore(storage).mirroredPackIds()) {
			for (JournalEntry entry : new JournalMirror(storage).entries(modpackId)) {
				claim(claims, distrusted, entry.policySha1(), -1);
				for (JournalEntry.Change change : entry.changes()) {
					claim(claims, distrusted, change.toSha1(), change.toSize());
					// Mirrors fetched from older servers carry no source size; only a positive size is a receipt, anything else stays unknown.
					claim(claims, distrusted, change.fromSha1(), change.fromSize() > 0 ? change.fromSize() : -1);
				}
			}
		}
		if (!distrusted.isEmpty()) LOGGER.warn("The journal mirror names {} objects with contradictory sizes; their sizes stay unvouched and are measured from the bytes", distrusted.size());
		return claims;
	}

	/** Records one server-authored claim: first claim wins, an unknown grows into a known size, a contradiction demotes the size to unknown for good. */
	private static void claim(TreeMap<String, Long> claims, Set<String> distrusted, String hash, long size) {
		if (hash == null) return;
		String normalized = HashUtils.normalizeSha1(hash);
		if (distrusted.contains(normalized)) return;
		Long known = claims.get(normalized);
		if (known == null) claims.put(normalized, size);
		else if (known >= 0 && size >= 0 && known.longValue() != size) {
			claims.put(normalized, -1L);
			distrusted.add(normalized);
		} else if (known < 0 && size >= 0) claims.put(normalized, size);
	}

	/** Folds history claims under the owned receipts: claims fill hashes local state does not know and never override a local receipt, so merging cannot throw. */
	static void mergeHistoryClaims(ExpectedSizes owned, Map<String, Long> claims) throws IOException {
		Map<String, Long> local = owned.sizes();
		int disagreements = 0;
		for (Map.Entry<String, Long> claim : claims.entrySet()) {
			Long knownSize = local.get(claim.getKey());
			if (knownSize == null) owned.optional(claim.getKey(), claim.getValue(), "journal history");
			else if (knownSize >= 0 && claim.getValue() >= 0 && knownSize.longValue() != claim.getValue().longValue()) disagreements++;
		}
		if (disagreements > 0) LOGGER.warn("Local state and the journal mirror disagree about the size of {} objects; keeping the locally verified receipts", disagreements);
	}

	/** Adds every durable client pin outside the mirror's history: overlays, generated copies, the state history, the pending transaction, and repair state. */
	static void collectNonHistoryReferences(ClientStorage storage, ExpectedSizes retained) throws IOException {
		collectOverlays(storage, retained);
		collectGeneratedCopies(storage, retained);
		collectStateJournal(storage, retained);
		collectTransaction(storage, retained);
		collectRepair(storage, retained);
		collectWaitingMusic(storage, retained);
		validateActiveProjection(storage);
	}

	/** Each pack's current head may advertise a waiting track; the hash is the only record, so the object stays pinned. */
	private static void collectWaitingMusic(ClientStorage storage, ExpectedSizes retained) throws IOException {
		HeadMirror heads = new HeadMirror(storage);
		for (String modpackId : new ClientGenerationStore(storage).mirroredPackIds()) {
			GenerationJsons.HeadDocumentFields head = heads.read(modpackId);
			if (head == null || !HashUtils.isSha1(head.waitingMusicSha1)) continue;
			retained.optional(head.waitingMusicSha1, -1, "waiting music");
		}
	}

	/** Every instance-tree file hash is a required pin, so cleanup cannot strand a snapshot. Forget-prefix is the only unpin. */
	private static void collectStateJournal(ClientStorage storage, ExpectedSizes retained) throws IOException {
		for (ClientStateJournal.Snapshot snapshot : ClientStateJournal.open(storage).entries()) {
			InstanceTree tree = InstanceTree.read(storage, snapshot.treeSha1());
			for (InstanceTree.TrackedFile file : tree.files()) retained.require(file.sha1(), file.size(), "instance timeline");
		}
	}

	private static void collectOverlays(ClientStorage storage, ExpectedSizes retained) throws IOException {
		try (FileCache metadata = FileCache.open(storage.fileCacheDirectory())) {
			for (Path modpack : childDirectories(storage.overlaysDirectory(), "client overlays")) {
				String modpackId = modpack.getFileName().toString();
				requireModpackId(modpackId, "client overlay directory");
				try (Stream<Path> files = Files.walk(modpack)) {
					for (Path file : files.filter(path -> !path.equals(modpack)).toList()) {
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

	private static void collectTransaction(ClientStorage storage, ExpectedSizes retained) throws IOException {
		UpdateTransaction transaction = UpdateTransaction.read(storage.transactionFile());
		if (transaction == null) return;
		for (UpdatePlan.Operation operation : transaction.plan().operations()) {
			retained.ifPresent(operation.expectedObjectHash(), operation.expectedSize(), "in-flight transaction operation");
			retained.ifPresent(operation.expectedExistingHash(), -1, "in-flight transaction source");
		}
		for (UpdatePlan.ProjectedFile projected : transaction.plan().projectedFinalState()) {
			if (projected.present()) retained.require(projected.expectedHash(), projected.expectedSize(), "in-flight transaction projection");
		}
		for (UpdatePlan.BaselineCapture capture : transaction.plan().baselineCaptures()) {
			if (!capture.absent()) retained.require(capture.expectedHash(), capture.expectedSize(), "in-flight transaction baseline");
		}
		for (UpdatePlan.Preservation preservation : transaction.plan().preservations()) {
			retained.require(preservation.expectedHash(), preservation.expectedSize(), "in-flight transaction preservation");
		}
		for (UpdatePlan.Conflict conflict : transaction.plan().conflicts()) {
			retained.optional(conflict.sourceHash(), conflict.sourceSize(), "in-flight transaction conflict source");
			retained.require(conflict.targetHash(), conflict.targetSize(), "in-flight transaction conflict target");
		}
	}

	private static void collectRepair(ClientStorage storage, ExpectedSizes retained) throws IOException {
		ClientStorageJsons.OfflineRepairJournalFields journal = ConfigTools
				.readState(storage.repairJournalFile(), ClientStorageJsons.OfflineRepairJournalFields.class, "Offline repair journal", OfflineRepair::validatedJournal)
				.orElse(null);
		if (journal == null) return;
		for (var reset : journal.editableResets) {
			retained.require(reset.defaultHash, reset.defaultSize, "offline repair editable default");
			retained.ifPresent(reset.currentHash, reset.currentSize, "offline repair editable source");
		}
		for (var mod : journal.unownedMods) {
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
			for (Path path : paths.toList()) {
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
			for (Path path : paths.filter(candidate -> !candidate.equals(directory)).toList()) {
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
			for (Path pack : packs.toList()) {
				FileTrees.requireNoSymbolicLink(pack, "client generated-copy state");
				if (!Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generated-copy state contains an unsupported entry: " + pack);
				requireModpackId(pack.getFileName().toString(), "client generated-copy directory");
				try (Stream<Path> generations = Files.list(pack)) {
					for (Path generation : generations.toList()) {
						FileTrees.requireNoSymbolicLink(generation, "client generated-copy state");
						if (!Files.isDirectory(generation, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generated-copy state contains an unsupported entry: " + generation);
						String contentToken = generation.getFileName().toString();
						if (!HashUtils.isCanonicalSha1(contentToken))
							throw new IOException("Client generated-copy directory is not canonical: " + contentToken);
						try (Stream<Path> states = Files.list(generation)) {
							for (Path state : states.toList()) {
								FileTrees.requireNoSymbolicLink(state, "client generated-copy state");
								String name = state.getFileName().toString();
								if (name.endsWith(DurableFiles.TEMPORARY_SUFFIX) || name.contains(DurableFiles.CORRUPT_ASIDE_MARKER)) {
									// A crash leftover or set-aside evidence never pins objects; loud, but never worth a boot.
									LOGGER.warn("Skipping {} in the client generated-copy state", name);
									continue;
								}
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
