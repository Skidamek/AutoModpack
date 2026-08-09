package pl.skidam.automodpack_core.utils.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.GeneratedCopyState;
import pl.skidam.automodpack_core.update.QuarantineArchive;
import pl.skidam.automodpack_core.update.RecoveryArchive;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Measures and explicitly maintains the client shared object store. */
public final class ClientObjectStore {
	private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");

	private ClientObjectStore() {}

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
			long generationRecordCount,
			long generationRecordBytes,
			long activeFileCount,
			long activeBytes,
			long metadataFileCount,
			long metadataBytes,
			long overlayFileCount,
			long overlayBytes,
			long baselineFileCount,
			long baselineBytes,
			long recoveryFileCount,
			long recoveryBytes,
			long quarantineFileCount,
			long quarantineBytes,
			long incomingFileCount,
			long incomingBytes,
			long backupFileCount,
			long backupBytes) {
		public StorageReport {
			if (List.of(objectCount, objectBytes, referencedObjectCount, referencedObjectBytes, validReferencedObjectCount, validReferencedObjectBytes,
					missingReferencedObjectCount, invalidReferencedObjectCount, generationRecordCount, generationRecordBytes, metadataFileCount, metadataBytes,
					overlayFileCount, overlayBytes, baselineFileCount, baselineBytes, recoveryFileCount, recoveryBytes, quarantineFileCount, quarantineBytes,
					incomingFileCount, incomingBytes, backupFileCount, backupBytes).stream().anyMatch(value -> value < 0))
				throw new IllegalArgumentException("Client storage report values cannot be negative");
			if (validReferencedObjectCount > referencedObjectCount || missingReferencedObjectCount > referencedObjectCount
					|| invalidReferencedObjectCount > referencedObjectCount - missingReferencedObjectCount)
				throw new IllegalArgumentException("Client storage reference counts are inconsistent");
		}

		public OptionalDouble referencedObjectCoverageRatio() {
			return referencedObjectCount == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) validReferencedObjectCount / referencedObjectCount);
		}

		public OptionalDouble referencedObjectSpaceRatio() {
			return objectBytes == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) validReferencedObjectBytes / objectBytes);
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

	/** A deterministic measurement of validated generated-copy state files. */
	public record GeneratedCopyReport(long count, long bytes) {
		public GeneratedCopyReport {
			if (count < 0 || bytes < 0) throw new IllegalArgumentException("Generated-copy totals cannot be negative");
		}
	}

	/** Measures all client state without deleting or writing anything. */
	public static StorageReport measure(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		ReferenceSet references = collectReferences(storage, null);
		return measure(storage, references);
	}

	/**
	 * Explicitly collects canonical, valid CAS objects not referenced by the selected client state.
	 * Generation records and recovery/quarantine data are never deleted by this method. Because this
	 * tranche does not prune records atomically, the supplied generation set must contain every
	 * installed record; the active generation is retained and validated as well.
	 */
	public static CollectionResult collectUnreachableObjects(ClientStorage storage, Set<String> retainedGenerationIds, Set<String> pinnedObjectHashes) throws IOException {
		Objects.requireNonNull(storage, "storage");
		Objects.requireNonNull(retainedGenerationIds, "retainedGenerationIds");
		Objects.requireNonNull(pinnedObjectHashes, "pinnedObjectHashes");
		Set<String> requestedGenerations = canonicalPins(retainedGenerationIds, "retained generation");
		ReferenceSet references = collectReferences(storage, requestedGenerations);
		for (String hash : canonicalPins(pinnedObjectHashes, "pinned object")) references.addOptional(hash, -1, "explicit pin");
		StorageReport before = measure(storage, references, true);
		List<Path> objects = regularFiles(storage.objectsDirectory(), "client object store");
		long deletedCount = 0;
		long deletedBytes = 0;
		for (Path object : objects) {
			String name = object.getFileName().toString();
			if (!object.getParent().equals(storage.objectsDirectory()) || !name.equals(name.toLowerCase(Locale.ROOT)) || !SHA1.matcher(name).matches()
					|| references.hashes().contains(name))
				continue;
			if (!isValidCanonicalObject(object, name.toLowerCase(Locale.ROOT))) continue;
			long size = Files.size(object);
			if (Files.deleteIfExists(object)) {
				deletedCount = addExact(deletedCount, 1, "deleted object count");
				deletedBytes = addExact(deletedBytes, size, "deleted object bytes");
			}
		}
		StorageReport after = measure(storage, references, true);
		return new CollectionResult(before, after, deletedCount, deletedBytes);
	}

	/** Returns every CAS hash named by all validated client state. */
	public static Set<String> referencedHashes(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		return collectReferences(storage, null).hashes();
	}

	/** Returns only referenced hashes whose verified object is physically present in this client CAS. */
	public static Set<String> existingReferencedHashes(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		ReferenceSet references = collectReferences(storage, null);
		TreeSet<String> existing = new TreeSet<>();
		for (var entry : references.sizes().entrySet()) {
			Path object = storage.objectsDirectory().resolve(entry.getKey()).normalize();
			if (!object.getParent().equals(storage.objectsDirectory()) || !Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)) continue;
			if (entry.getValue() >= 0 && Files.size(object) != entry.getValue()) continue;
			if (entry.getKey().equals(HashUtils.getHash(object))) existing.add(entry.getKey());
		}
		return Set.copyOf(existing);
	}

	/** Validates all durable client state and all required CAS references without mutating storage. */
	public static StorageReport validate(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		ReferenceSet references = collectReferences(storage, null);
		return measure(storage, references, true);
	}

	/** Measures generated-copy state after validating its pack, generation, and selection identities. */
	public static GeneratedCopyReport measureGeneratedCopies(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		FileTotals totals = new FileTotals(0, 0);
		for (Path path : generatedCopyFiles(storage)) totals = totals.plus(fileTotals(List.of(path)));
		return new GeneratedCopyReport(totals.count(), totals.bytes());
	}

	private static StorageReport measure(ClientStorage storage, ReferenceSet references) throws IOException {
		return measure(storage, references, false);
	}

	private static StorageReport measure(ClientStorage storage, ReferenceSet references, boolean requireRequiredReferences) throws IOException {
		FileTotals objects = fileTotals(regularFiles(storage.objectsDirectory(), "client object store"));
		ReferenceTotals referenceTotals = measureReferences(storage, references, requireRequiredReferences);
		FileTotals records = fileTotals(regularFiles(storage.recordsDirectory(), "client generation records"));
		FileTotals active = fileTotals(regularFiles(storage.activeDirectory(), "client active projection"));
		FileTotals metadata = metadataTotals(storage);
		FileTotals overlays = fileTotals(regularFiles(storage.overlaysDirectory(), "client overlays"));
		FileTotals baselines = fileTotals(regularFiles(storage.baselinesDirectory(), "client baselines"));
		FileTotals recovery = fileTotals(regularFiles(storage.recoveryDirectory(), "client recovery archives"));
		FileTotals quarantine = fileTotals(regularFiles(storage.quarantineDirectory(), "client quarantine archives"));
		FileTotals incoming = fileTotals(regularFiles(storage.incomingDirectory(), "client incoming transactions"));
		FileTotals backup = fileTotals(regularFiles(storage.backupDirectory(), "client transaction backups"));
		return new StorageReport(objects.count(), objects.bytes(), references.hashes().size(), referenceTotals.expectedBytes(), referenceTotals.validCount(), referenceTotals.validBytes(),
				referenceTotals.missingCount(), referenceTotals.invalidCount(), records.count(), records.bytes(), active.count(), active.bytes(), metadata.count(), metadata.bytes(), overlays.count(), overlays.bytes(),
				baselines.count(), baselines.bytes(), recovery.count(), recovery.bytes(), quarantine.count(), quarantine.bytes(), incoming.count(), incoming.bytes(), backup.count(), backup.bytes());
	}

	private static ReferenceSet collectReferences(ClientStorage storage, Set<String> retainedGenerationIds) throws IOException {
		ReferenceSet retained = new ReferenceSet();
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		List<String> generationIds = generationIds(storage);
		Map<String, GenerationRecord> records = new TreeMap<>();
		for (String generationId : generationIds) {
			if (!SHA1.matcher(generationId).matches() || !generationId.equals(generationId.toLowerCase(Locale.ROOT))) throw new IOException("Client generation directory is not canonical: " + generationId);
			GenerationRecord record;
			try {
				record = generations.read(generationId).orElseThrow(() -> new IOException("Client generation record is missing: " + generationId));
			} catch (RuntimeException e) {
				throw new IOException("Client generation record is invalid: " + generationId, e);
			}
			records.put(generationId, record);
		}
		TreeSet<String> selected = new TreeSet<>();
		if (retainedGenerationIds == null) selected.addAll(records.keySet());
		else {
			if (!retainedGenerationIds.equals(records.keySet()))
				throw new IOException("Cannot collect while generation records would remain without their objects; retain every installed generation record");
			selected.addAll(records.keySet());
		}
		ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
		if (activeState != null) {
			GenerationRecord active = records.get(activeState.generationId);
			if (active == null) throw new IOException("Active client generation record is missing: " + activeState.generationId);
			if (!active.manifest().modpackId().equals(activeState.modpackId)) throw new IOException("Active client generation identity is inconsistent");
			selected.add(activeState.generationId);
		}
		for (String generationId : selected) {
			GenerationRecord record = records.get(generationId);
			if (record == null) throw new IOException("Retained client generation is not installed: " + generationId);
			addRecordReferences(retained, record);
		}
		collectBaselines(storage, retained);
		collectOverlays(storage, retained);
		collectGeneratedCopies(storage, retained);
		collectRecovery(storage, retained);
		collectQuarantine(storage, retained);
		collectTransaction(storage, retained);
		collectActiveProjection(storage, retained, activeState);
		return retained;
	}

	private static void addRecordReferences(ReferenceSet retained, GenerationRecord record) throws IOException {
		for (var group : record.manifest().groups().values()) for (var file : group.files().values()) retained.addRequired(file.sha1(), file.size(), "generation manifest");
		for (var entry : record.ownershipLedger().entries().values()) for (var content : entry.historicalHashes()) retained.addRequired(content.sha1(), content.size(), "ownership ledger");
	}

	private static void collectBaselines(ClientStorage storage, ReferenceSet retained) throws IOException {
		for (Path modpack : childDirectories(storage.baselinesDirectory(), "client baselines")) {
			String modpackId = modpack.getFileName().toString();
			requireModpackId(modpackId, "client baseline directory");
			Path baseline = modpack.resolve("baseline.json");
			if (!Files.exists(baseline, LinkOption.NOFOLLOW_LINKS)) continue;
			ensureRegular(baseline, "client baseline");
			ClientStorageJsons.ClientBaselineFields fields = readJson(baseline, ClientStorageJsons.ClientBaselineFields.class, "client baseline");
			if (fields.schemaVersion != 1 || !modpackId.equals(fields.modpackId) || fields.entries == null) throw new IOException("Client baseline identity is invalid: " + baseline);
			for (var entry : fields.entries) {
				if (entry == null || entry.logicalPath == null || entry.objectHash == null) throw new IOException("Client baseline entry is incomplete: " + baseline);
				if (entry.absent) {
					if (!entry.objectHash.isEmpty() || entry.size != -1) throw new IOException("Absent client baseline entry has content: " + baseline);
				} else {
					if (entry.size < 0) throw new IOException("Client baseline entry size is invalid: " + baseline);
					retained.addRequired(entry.objectHash, entry.size, "client baseline");
				}
			}
		}
	}

	private static void collectOverlays(ClientStorage storage, ReferenceSet retained) throws IOException {
		for (Path modpack : childDirectories(storage.overlaysDirectory(), "client overlays")) {
			String modpackId = modpack.getFileName().toString();
			requireModpackId(modpackId, "client overlay directory");
			try (Stream<Path> files = Files.walk(modpack)) {
				for (Path file : files.filter(path -> !path.equals(modpack)).sorted().toList()) {
					ensureNoSymbolicLink(file, "client overlay");
					if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) retained.addOptional(HashUtils.getHash(file), Files.size(file), "client overlay");
				}
			}
		}
	}

	private static void collectGeneratedCopies(ClientStorage storage, ReferenceSet retained) throws IOException {
		for (Path path : generatedCopyFiles(storage)) {
			Path generationDirectory = path.getParent();
			Path packDirectory = generationDirectory.getParent();
			String modpackId = packDirectory.getFileName().toString();
			String generationId = generationDirectory.getFileName().toString();
			String selectionDigest = path.getFileName().toString().substring(0, 40);
			GeneratedCopyState state = GeneratedCopyState.read(storage, modpackId, generationId, selectionDigest);
			for (GeneratedCopyState.Entry entry : state.entries()) retained.addOptional(entry.sha1(), entry.size(), "generated-copy state");
		}
	}

	private static void collectRecovery(ClientStorage storage, ReferenceSet retained) throws IOException {
		for (Path modpack : childDirectories(storage.recoveryDirectory(), "client recovery archives")) {
			requireModpackId(modpack.getFileName().toString(), "client recovery directory");
			ClientStorageJsons.ClientRecoveryArchiveFields archive = RecoveryArchive.read(modpack);
			for (var entry : archive.entries) retained.addOptional(entry.sha1, entry.size, "recovery archive");
		}
	}

	private static void collectQuarantine(ClientStorage storage, ReferenceSet retained) throws IOException {
		for (Path modpack : childDirectories(storage.quarantineDirectory(), "client quarantine archives")) {
			String modpackId = requireModpackId(modpack.getFileName().toString(), "client quarantine directory");
			ClientStorageJsons.ClientQuarantineFields archive = QuarantineArchive.read(storage, modpackId);
			for (var entry : archive.entries) retained.addOptional(entry.sourceHash, entry.sourceSize, "quarantine archive");
		}
	}

	private static void collectTransaction(ClientStorage storage, ReferenceSet retained) throws IOException {
		Path transactionPath = storage.transactionFile();
		if (!Files.exists(transactionPath, LinkOption.NOFOLLOW_LINKS)) return;
		ensureRegular(transactionPath, "client transaction");
		UpdateTransaction transaction = readJson(transactionPath, UpdateTransaction.class, "client transaction");
		if (transaction.schemaVersion != UpdateTransaction.CURRENT_SCHEMA_VERSION || transaction.operations == null || transaction.projectedFinalState == null
				|| transaction.plannedPreservations == null || transaction.plannedBaselineCaptures == null || transaction.plannedConflicts == null)
			throw new IOException("Client transaction fields are incomplete: " + transactionPath);
		for (UpdatePlan.Operation operation : transaction.operations) {
			if (operation == null) throw new IOException("Client transaction contains an incomplete operation");
			retained.addIfPresent(operation.expectedObjectHash(), operation.expectedSize(), "in-flight transaction operation");
			retained.addIfPresent(operation.expectedExistingHash(), -1, "in-flight transaction source");
		}
		for (UpdatePlan.ProjectedFile projected : transaction.projectedFinalState) {
			if (projected == null) throw new IOException("Client transaction contains an incomplete projection");
			if (projected.present()) retained.addRequired(projected.expectedHash(), projected.expectedSize(), "in-flight transaction projection");
		}
		for (UpdatePlan.BaselineCapture capture : transaction.plannedBaselineCaptures) {
			if (capture == null) throw new IOException("Client transaction contains an incomplete baseline capture");
			if (!capture.absent()) retained.addRequired(capture.expectedHash(), capture.expectedSize(), "in-flight transaction baseline");
		}
		for (UpdatePlan.Preservation preservation : transaction.plannedPreservations) {
			if (preservation == null) throw new IOException("Client transaction contains an incomplete preservation");
			retained.addRequired(preservation.expectedHash(), preservation.expectedSize(), "in-flight transaction preservation");
		}
		for (UpdatePlan.Conflict conflict : transaction.plannedConflicts) {
			if (conflict == null) throw new IOException("Client transaction contains an incomplete conflict");
			retained.addOptional(conflict.sourceHash(), conflict.sourceSize(), "in-flight transaction conflict source");
			retained.addRequired(conflict.targetHash(), conflict.targetSize(), "in-flight transaction conflict target");
		}
	}

	private static void collectActiveProjection(ClientStorage storage, ReferenceSet retained, ClientStorageJsons.ClientGenerationStateFields activeState) throws IOException {
		if (!Files.exists(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) return;
		for (Path file : regularFiles(storage.activeDirectory(), "client active projection")) retained.addOptional(HashUtils.getHash(file), Files.size(file), "active projection");
		if (activeState != null && !Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client active projection is not a directory");
	}

	private static ReferenceTotals measureReferences(ClientStorage storage, ReferenceSet references, boolean requireRequiredReferences) throws IOException {
		long expectedBytes = 0;
		long validCount = 0;
		long validBytes = 0;
		long missingCount = 0;
		long invalidCount = 0;
		for (var entry : references.sizes().entrySet()) {
			String hash = entry.getKey();
			long expectedSize = entry.getValue();
			if (expectedSize >= 0) expectedBytes = addExact(expectedBytes, expectedSize, "referenced object bytes");
			Path object = storage.objectsDirectory().resolve(hash).normalize();
			if (!object.getParent().equals(storage.objectsDirectory()) || Files.isSymbolicLink(object) || !Files.exists(object, LinkOption.NOFOLLOW_LINKS)) {
				if (requireRequiredReferences && references.required().contains(hash)) throw new IOException("Required client object is missing: " + hash);
				missingCount = addExact(missingCount, 1, "missing referenced object count");
				continue;
			}
			boolean valid = Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS);
			long actualSize = valid ? Files.size(object) : -1;
			String actualHash = valid ? HashUtils.getHash(object) : null;
			if (!valid || expectedSize >= 0 && actualSize != expectedSize || !hash.equals(actualHash)) {
				if (requireRequiredReferences && references.required().contains(hash)) throw new IOException("Required client object is corrupt: " + hash);
				invalidCount = addExact(invalidCount, 1, "invalid referenced object count");
				continue;
			}
			validCount = addExact(validCount, 1, "valid referenced object count");
			validBytes = addExact(validBytes, Files.size(object), "valid referenced object bytes");
		}
		return new ReferenceTotals(expectedBytes, validCount, validBytes, missingCount, invalidCount);
	}

	private static FileTotals metadataTotals(ClientStorage storage) throws IOException {
		FileTotals total = fileTotals(regularFiles(storage.fileMetadataDirectory(), "client file metadata"));
		total = total.plus(fileTotals(regularFiles(storage.modMetadataDirectory(), "client mod metadata")));
		total = total.plus(fileTotals(regularFiles(storage.packsDirectory(), "client pack metadata")));
		for (Path file : List.of(storage.stateFile(), storage.selectionFile(), storage.clientConfigFile(), storage.restartLoopStateFile(), storage.modpackContentTempFile()))
			if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) total = total.plus(fileTotals(List.of(ensureRegular(file, "client metadata"))));
		return total;
	}

	private static List<String> generationIds(ClientStorage storage) throws IOException {
		Path root = storage.recordsDirectory();
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
		ensureDirectory(root, "client generation records");
		try (Stream<Path> paths = Files.list(root)) {
			List<String> result = new ArrayList<>();
			for (Path path : paths.sorted().toList()) {
				ensureNoSymbolicLink(path, "client generation records");
				if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generation records contain an unsupported entry: " + path);
				result.add(path.getFileName().toString());
			}
			return List.copyOf(result);
		}
	}

	private static List<Path> childDirectories(Path root, String description) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
		ensureDirectory(root, description);
		try (Stream<Path> paths = Files.list(root)) {
			List<Path> result = new ArrayList<>();
			for (Path path : paths.sorted().toList()) {
				ensureNoSymbolicLink(path, description);
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) result.add(path);
				else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException(description + " contains an unsupported entry: " + path);
			}
			return result;
		}
	}

	private static List<Path> regularFiles(Path directory, String description) throws IOException {
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
		ensureDirectory(directory, description);
		try (Stream<Path> paths = Files.walk(directory)) {
			List<Path> result = new ArrayList<>();
			for (Path path : paths.filter(candidate -> !candidate.equals(directory)).sorted().toList()) {
				ensureNoSymbolicLink(path, description);
				if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) result.add(path);
			}
			return List.copyOf(result);
		}
	}

	private static List<Path> generatedCopyFiles(ClientStorage storage) throws IOException {
		Path root = storage.generatedCopiesDirectory();
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
		ensureDirectory(root, "client generated-copy state");
		List<Path> result = new ArrayList<>();
		try (Stream<Path> packs = Files.list(root)) {
			for (Path pack : packs.sorted().toList()) {
				ensureNoSymbolicLink(pack, "client generated-copy state");
				if (!Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generated-copy state contains an unsupported entry: " + pack);
				requireModpackId(pack.getFileName().toString(), "client generated-copy directory");
				try (Stream<Path> generations = Files.list(pack)) {
					for (Path generation : generations.sorted().toList()) {
						ensureNoSymbolicLink(generation, "client generated-copy state");
						if (!Files.isDirectory(generation, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generated-copy state contains an unsupported entry: " + generation);
						String generationId = generation.getFileName().toString();
						if (!SHA1.matcher(generationId).matches() || !generationId.equals(generationId.toLowerCase(Locale.ROOT)))
							throw new IOException("Client generated-copy directory is not canonical: " + generationId);
						try (Stream<Path> states = Files.list(generation)) {
							for (Path state : states.sorted().toList()) {
								ensureNoSymbolicLink(state, "client generated-copy state");
								String name = state.getFileName().toString();
								if (!Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS) || !name.matches("[0-9a-f]{40}\\.json"))
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

	private static FileTotals fileTotals(List<Path> paths) throws IOException {
		long bytes = 0;
		for (Path path : paths) bytes = addExact(bytes, Files.size(path), "client storage bytes");
		return new FileTotals(paths.size(), bytes);
	}

	private static boolean isValidCanonicalObject(Path object, String hash) {
		return !Files.isSymbolicLink(object) && Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS) && hash.equals(HashUtils.getHash(object));
	}

	private static <T> T readJson(Path path, Class<T> type, String description) throws IOException {
		try {
			return ConfigTools.read(path, type).orElseThrow(() -> new IOException(description + " is empty: " + path));
		} catch (RuntimeException e) {
			throw new IOException(description + " is invalid: " + path, e);
		}
	}

	private static Path ensureRegular(Path path, String description) throws IOException {
		ensureNoSymbolicLink(path, description);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException(description + " is not a regular file: " + path);
		return path;
	}

	private static void ensureDirectory(Path path, String description) throws IOException {
		ensureNoSymbolicLink(path, description);
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException(description + " is not a directory: " + path);
	}

	private static void ensureNoSymbolicLink(Path path, String description) throws IOException {
		if (Files.isSymbolicLink(path)) throw new IOException(description + " contains a symbolic link: " + path);
	}

	private static String requireModpackId(String value, String description) throws IOException {
		try {
			return ModpackId.requireValid(value);
		} catch (RuntimeException e) {
			throw new IOException("Invalid " + description + ": " + value, e);
		}
	}

	private static Set<String> canonicalPins(Set<String> pins, String description) throws IOException {
		TreeSet<String> result = new TreeSet<>();
		for (String pin : pins) {
			try {
				result.add(normalizeHash(pin));
			} catch (IllegalArgumentException e) {
				throw new IOException("Invalid " + description + " hash: " + pin, e);
			}
		}
		return Set.copyOf(result);
	}

	public static String normalizeHash(String sha1) {
		if (sha1 == null || !SHA1.matcher(sha1).matches()) throw new IllegalArgumentException("Invalid client object SHA-1");
		return sha1.toLowerCase(Locale.ROOT);
	}

	private static long addExact(long first, long second, String description) throws IOException {
		try {
			return Math.addExact(first, second);
		} catch (ArithmeticException e) {
			throw new IOException("Overflow while measuring " + description, e);
		}
	}

	private record FileTotals(long count, long bytes) {
		private FileTotals {
			if (count < 0 || bytes < 0) throw new IllegalArgumentException("File totals cannot be negative");
		}

		private FileTotals plus(FileTotals other) throws IOException {
			return new FileTotals(addExact(count, other.count, "client storage file count"), addExact(bytes, other.bytes, "client storage bytes"));
		}
	}

	private record ReferenceTotals(long expectedBytes, long validCount, long validBytes, long missingCount, long invalidCount) {}

	private static final class ReferenceSet {
		private final Map<String, Long> sizes = new TreeMap<>();
		private final Set<String> required = new HashSet<>();

		private void addRequired(String hash, long size, String source) throws IOException {
			add(hash, size, source);
			required.add(normalizeHash(hash));
		}

		private void addOptional(String hash, long size, String source) throws IOException {
			if (hash == null || hash.isBlank()) throw new IOException("Missing client object reference from " + source);
			add(hash, size, source);
		}

		private void addIfPresent(String hash, long size, String source) throws IOException {
			if (hash != null && !hash.isBlank()) add(hash, size, source);
		}

		private void add(String hash, long size, String source) throws IOException {
			String normalized;
			try {
				normalized = normalizeHash(hash);
			} catch (IllegalArgumentException e) {
				throw new IOException("Invalid client object reference from " + source + ": " + hash, e);
			}
			if (size < -1) throw new IOException("Invalid client object size from " + source + ": " + size);
			Long previous = sizes.putIfAbsent(normalized, size);
			if (previous != null && size >= 0 && previous >= 0 && previous.longValue() != size) throw new IOException("Conflicting client object sizes for " + normalized);
			if (previous != null && previous == -1 && size >= 0) sizes.put(normalized, size);
		}

		private Set<String> hashes() {
			return Set.copyOf(sizes.keySet());
		}

		private Map<String, Long> sizes() {
			return Map.copyOf(sizes);
		}

		private Set<String> required() {
			return Set.copyOf(required);
		}
	}
}
