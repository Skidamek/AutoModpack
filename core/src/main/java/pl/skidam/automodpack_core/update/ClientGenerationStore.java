package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.CatalogueSnapshot;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryIndex;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.cache.ClientObjectStore;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

/** Persistent immutable client snapshots of downloaded generation records. The server store is compact; client snapshots remain complete so local history and cached switching work without server access. */
public final class ClientGenerationStore {
	private final ClientStorage storage;

	/** A deterministic receipt for one explicit client generation compaction pass. */
	public record CompactionResult(
			List<String> retainedGenerationIds,
			List<String> removedGenerationIds,
			long generationRecordCountBefore,
			long generationRecordBytesBefore,
			long generationRecordCountAfter,
			long generationRecordBytesAfter,
			long generatedCopyCountBefore,
			long generatedCopyBytesBefore,
			long generatedCopyCountAfter,
			long generatedCopyBytesAfter,
			ClientObjectStore.CollectionResult objectCollection) {
		public CompactionResult {
			retainedGenerationIds = sortedIds(retainedGenerationIds, "retained generation IDs");
			removedGenerationIds = sortedIds(removedGenerationIds, "removed generation IDs");
			if (List.of(generationRecordCountBefore, generationRecordBytesBefore, generationRecordCountAfter, generationRecordBytesAfter, generatedCopyCountBefore,
					generatedCopyBytesBefore, generatedCopyCountAfter, generatedCopyBytesAfter).stream().anyMatch(value -> value < 0))
				throw new IllegalArgumentException("Compaction receipt values cannot be negative");
			if (!Collections.disjoint(retainedGenerationIds, removedGenerationIds)) throw new IllegalArgumentException("Compaction retained and removed IDs overlap");
			objectCollection = Objects.requireNonNull(objectCollection, "object collection receipt");
		}

		private static List<String> sortedIds(List<String> ids, String description) {
			Objects.requireNonNull(ids, description);
			return ids.stream().map(id -> {
				try {
					return ClientObjectStore.normalizeHash(id);
				} catch (IllegalArgumentException e) {
					throw new IllegalArgumentException("Invalid " + description + ": " + id, e);
				}
			}).distinct().sorted().toList();
		}
	}

	private record GenerationSnapshot(Map<String, GenerationRecord> records, Set<String> retainedGenerationIds, Set<String> removedGenerationIds) {}
	private record FileTotals(long count, long bytes) {}

	public ClientGenerationStore(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage);
	}

	public void write(GenerationRecord record) throws IOException {
		write(record, GenerationPatchNoteHistory.forRecord(record), null);
	}

	public void write(GenerationRecord record, List<GenerationPatchNoteHistory.Entry> patchNotesHistory) throws IOException {
		write(record, patchNotesHistory, null);
	}

	/** Persists the server's thin lineage index alongside the complete local generation record. */
	public void write(GenerationRecord record, List<GenerationPatchNoteHistory.Entry> patchNotesHistory, GenerationHistoryIndex historyIndex) throws IOException {
		ClientStorageMutation.run(storage, () -> {
			writeLocked(record, patchNotesHistory, historyIndex);
			return null;
		});
	}

	private void writeLocked(GenerationRecord record, List<GenerationPatchNoteHistory.Entry> patchNotesHistory, GenerationHistoryIndex historyIndex) throws IOException {
		Objects.requireNonNull(record, "record");
		Objects.requireNonNull(patchNotesHistory, "patchNotesHistory");
		Path path = storage.generationManifest(record.metadata().generationId());
		ModpackJsons.CompleteModpackContentFields fields = record.toFields();
		GenerationPatchNoteHistory.writeFields(fields, patchNotesHistory);
		if (historyIndex != null) fields.generationHistory = historyIndex.toFields();
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			ModpackJsons.CompleteModpackContentFields existingFields = readFields(path).orElseThrow(() -> new IOException("Stored client generation is invalid: " + path));
			GenerationRecord existing = GenerationRecord.fromFields(existingFields);
			if (!existing.equals(record)) throw new IOException("Client generation record already exists with different content: " + path);
			List<GenerationPatchNoteHistory.Entry> effectivePatchNotesHistory = compatiblePatchNotesHistory(existingFields, fields, patchNotesHistory, path);
			GenerationPatchNoteHistory.writeFields(fields, effectivePatchNotesHistory);
			GenerationHistoryIndex existingHistoryIndex = existingFields.generationHistory == null ? null : GenerationHistoryIndex.fromFields(existingFields.generationHistory);
			GenerationHistoryIndex effectiveHistoryIndex = compatibleHistoryIndex(existingHistoryIndex, historyIndex, path);
			if (effectiveHistoryIndex == null) fields.generationHistory = null;
			else fields.generationHistory = effectiveHistoryIndex.toFields();
			boolean historyChanged = !Objects.equals(existingHistoryIndex, effectiveHistoryIndex);
			boolean patchNotesChanged = !GenerationPatchNoteHistory.fromFields(existingFields).equals(effectivePatchNotesHistory)
					|| hasCompletePatchNotesHistory(existingFields) != hasCompletePatchNotesHistory(fields);
			if (patchNotesChanged || historyChanged) {
				ConfigTools.writeAtomic(path, fields);
				verify(path, record, effectivePatchNotesHistory, effectiveHistoryIndex);
			}
			return;
		}
		Files.createDirectories(path.getParent());
		ConfigTools.writeAtomic(path, fields);
		verify(path, record, patchNotesHistory, historyIndex);
	}

	private static List<GenerationPatchNoteHistory.Entry> compatiblePatchNotesHistory(ModpackJsons.CompleteModpackContentFields existingFields,
			ModpackJsons.CompleteModpackContentFields requestedFields, List<GenerationPatchNoteHistory.Entry> requestedHistory, Path path) throws IOException {
		List<GenerationPatchNoteHistory.Entry> existingHistory = GenerationPatchNoteHistory.fromFields(existingFields);
		boolean existingComplete = hasCompletePatchNotesHistory(existingFields);
		boolean requestedComplete = hasCompletePatchNotesHistory(requestedFields);
		if (existingComplete && requestedComplete && !existingHistory.equals(requestedHistory))
			throw new IOException("Client generation patch-note history already exists with different content: " + path);
		return existingComplete ? existingHistory : requestedHistory;
	}

	private static boolean hasCompletePatchNotesHistory(ModpackJsons.CompleteModpackContentFields fields) {
		return fields.patchNotesHistory != null && !fields.patchNotesHistory.isEmpty();
	}

	private static GenerationHistoryIndex compatibleHistoryIndex(GenerationHistoryIndex existing, GenerationHistoryIndex requested, Path path) throws IOException {
		if (existing == null) return requested;
		if (requested == null) return existing;
		if (!existing.modpackId().equals(requested.modpackId()) || !existing.currentGenerationId().equals(requested.currentGenerationId())
				|| existing.entries().size() != requested.entries().size())
			throw new IOException("Client generation history index already exists with different content: " + path);
		List<GenerationHistoryIndex.Entry> merged = new ArrayList<>(existing.entries().size());
		for (int index = 0; index < existing.entries().size(); index++) {
			GenerationHistoryIndex.Entry stored = existing.entries().get(index);
			GenerationHistoryIndex.Entry incoming = requested.entries().get(index);
			if (!sameHistoryEntryContent(stored, incoming)) throw new IOException("Client generation history index already exists with different content: " + path);
			merged.add(new GenerationHistoryIndex.Entry(stored.generationId(), stored.parentGenerationId(), stored.createdAt(), stored.stateDigest(), stored.rollbackTargetGenerationId(), stored.patchNotes(),
					stored.patchNotesDigest(), stored.diffSummary(), stored.diffDigest(), stored.detailsAvailable() || incoming.detailsAvailable(), stored.rollbackAvailable() || incoming.rollbackAvailable()));
		}
		int boundaryIndex = -1;
		for (int index = 0; index < merged.size(); index++) {
			if (merged.get(index).rollbackAvailable()) {
				boundaryIndex = index;
				break;
			}
		}
		if (boundaryIndex < 0) throw new IOException("Client generation history index has no rollback boundary: " + path);
		return new GenerationHistoryIndex(existing.modpackId(), existing.currentGenerationId(), boundaryIndex == 0 ? "" : merged.get(boundaryIndex).generationId(), merged);
	}

	private static boolean sameHistoryEntryContent(GenerationHistoryIndex.Entry first, GenerationHistoryIndex.Entry second) {
		return first.generationId().equals(second.generationId()) && first.parentGenerationId().equals(second.parentGenerationId()) && first.createdAt().equals(second.createdAt())
				&& first.stateDigest().equals(second.stateDigest()) && first.rollbackTargetGenerationId().equals(second.rollbackTargetGenerationId()) && first.patchNotes().equals(second.patchNotes())
				&& first.patchNotesDigest().equals(second.patchNotesDigest()) && first.diffSummary().equals(second.diffSummary()) && first.diffDigest().equals(second.diffDigest());
	}

	public Optional<GenerationRecord> read(String generationId) throws IOException {
		return readFields(generationId).map(GenerationRecord::fromFields);
	}

	public Optional<ModpackJsons.CompleteModpackContentFields> readFields(String generationId) throws IOException {
		return readFields(storage.generationManifest(generationId));
	}

	public Optional<GenerationHistoryIndex> historyIndex(String generationId) throws IOException {
		Optional<ModpackJsons.CompleteModpackContentFields> fields = readFields(generationId);
		if (fields.isEmpty() || fields.orElseThrow().generationHistory == null) return Optional.empty();
		return Optional.of(GenerationHistoryIndex.fromFields(fields.orElseThrow().generationHistory));
	}

	/** Retrieves one selected historical catalogue through the authenticated object-transfer session. */
	public CompletableFuture<CatalogueSnapshot> downloadHistoricalCatalogue(DownloadClient client, GenerationHistoryIndex.Entry entry, Path destination,
			IntConsumer chunkCallback) {
		Objects.requireNonNull(client, "download client");
		Objects.requireNonNull(entry, "history entry");
		Objects.requireNonNull(destination, "destination");
		if (!entry.detailsAvailable()) return CompletableFuture.failedFuture(new IOException("Historical catalogue details were compacted: " + entry.generationId()));
		try {
			Files.createDirectories(destination.toAbsolutePath().normalize().getParent());
		} catch (IOException e) {
			return CompletableFuture.failedFuture(e);
		}
		return client.downloadHistoricalCatalogue(entry.stateDigest(), destination, chunkCallback).thenApply(path -> readHistoricalCatalogue(path, entry)).handle((snapshot, failure) -> {
			IOException cleanupFailure = null;
			try {
				Files.deleteIfExists(destination);
			} catch (IOException e) {
				cleanupFailure = e;
			}
			if (failure != null) {
				if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
				throw failure instanceof CompletionException completionException ? completionException : new CompletionException(failure);
			}
			if (cleanupFailure != null) throw new CompletionException(cleanupFailure);
			return snapshot;
		});
	}

	private static CatalogueSnapshot readHistoricalCatalogue(Path path, GenerationHistoryIndex.Entry entry) {
		Throwable failure = null;
		try {
			GenerationJsons.CatalogueSnapshotFields catalogueFields = ConfigTools.parse(Files.readString(path, StandardCharsets.UTF_8), GenerationJsons.CatalogueSnapshotFields.class);
			CatalogueSnapshot snapshot = CatalogueSnapshot.fromFields(catalogueFields);
			if (!snapshot.stateDigest().equals(entry.stateDigest())) throw new IOException("Historical catalogue identity does not match history index");
			return snapshot;
		} catch (IOException | RuntimeException e) {
			failure = e;
			throw new CompletionException("Historical catalogue is invalid", e);
		} finally {
			try {
				Files.deleteIfExists(path);
			} catch (IOException cleanupFailure) {
				if (failure != null) failure.addSuppressed(cleanupFailure);
				else throw new CompletionException("Historical catalogue temporary file could not be deleted", cleanupFailure);
			}
		}
	}

	/** Reconstructs the active target from one validated generation record and the persisted selection intent. */
	public Optional<SelectedModpackTarget> readActiveTarget(ClientPlatform platform) throws IOException {
		Objects.requireNonNull(platform, "platform");
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state == null) return Optional.empty();
		ModpackJsons.CompleteModpackContentFields fields = readFields(state.generationId)
				.orElseThrow(() -> new IOException("Active client generation record is missing: " + state.generationId));
		GenerationRecord record = GenerationRecord.fromFields(fields);
		if (!Objects.equals(state.modpackId, record.manifest().modpackId()))
			throw new IOException("Active client state and generation record belong to different modpacks");
		Optional<SelectionIntent> stored = new ClientSelectionStore(storage.selectionFile()).get(state.modpackId);
		return stored.isPresent()
				? Optional.of(SelectedModpackTarget.prepare(fields, null, stored.get(), platform))
				: Optional.of(SelectedModpackTarget.prepareDefault(fields, platform));
	}

	public List<GenerationPatchNoteHistory.Entry> patchNotesHistory(String generationId) throws IOException {
		return readFields(generationId).map(GenerationPatchNoteHistory::fromFields).orElse(List.of());
	}

	public List<String> generationIds() throws IOException {
		if (!Files.exists(storage.recordsDirectory(), LinkOption.NOFOLLOW_LINKS)) return List.of();
		try (Stream<Path> paths = Files.list(storage.recordsDirectory())) {
			return paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	/**
	 * Explicitly compacts stale client generation records and their generated-copy state.
	 *
	 * <p>
	 * The newest record for each installed modpack is retained, together with the record
	 * selected by active-state.json when it is older. No arbitrary count or age limit is used.
	 * All validation completes before the first deletion. The existing CAS collector then validates
	 * and collects objects using the records that remain.
	 * </p>
	 */
	public CompactionResult compact() throws IOException {
		return ClientStorageMutation.run(storage, this::compactLocked);
	}

	private CompactionResult compactLocked() throws IOException {
		recoverCompactionLocked();
		if (Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Cannot compact client storage while an update transaction is active: " + storage.transactionFile());
		GenerationSnapshot snapshot = validateCompactionSnapshot();
		ClientObjectStore.validate(storage);
		FileTotals recordsBefore = generationTotals(snapshot.records().keySet());
		ClientObjectStore.GeneratedCopyReport generatedBefore = ClientObjectStore.measureGeneratedCopies(storage);

		writeCompactionJournal(snapshot.removedGenerationIds());
		deleteJournaledGenerations(snapshot.removedGenerationIds());

		ClientObjectStore.CollectionResult objectCollection = ClientObjectStore.collectUnreachableObjects(storage, snapshot.retainedGenerationIds(), Set.of());
		finishCompactionJournal();
		try (FileMetadataCache metadata = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			metadata.cleanup();
		}
		FileTotals recordsAfter = generationTotals(snapshot.retainedGenerationIds());
		ClientObjectStore.GeneratedCopyReport generatedAfter = ClientObjectStore.measureGeneratedCopies(storage);
		return new CompactionResult(List.copyOf(snapshot.retainedGenerationIds()), List.copyOf(snapshot.removedGenerationIds()), recordsBefore.count(), recordsBefore.bytes(),
				recordsAfter.count(), recordsAfter.bytes(), generatedBefore.count(), generatedBefore.bytes(), generatedAfter.count(), generatedAfter.bytes(), objectCollection);
	}

	/** Resumes an interrupted explicit compaction before normal client work starts. */
	public void recoverCompaction() throws IOException {
		ClientStorageMutation.run(storage, () -> {
			recoverCompactionLocked();
			return null;
		});
	}

	private void recoverCompactionLocked() throws IOException {
		if (!Files.exists(storage.compactionJournalFile(), LinkOption.NOFOLLOW_LINKS)) return;
		ClientStorageJsons.ClientCompactionJournalFields journal = ConfigTools.read(storage.compactionJournalFile(), ClientStorageJsons.ClientCompactionJournalFields.class)
				.orElseThrow(() -> new IOException("Client compaction journal is empty"));
		if (journal.schemaVersion != 1 || journal.removedGenerationIds == null) throw new IOException("Client compaction journal is invalid");
		Set<String> removed = new TreeSet<>();
		for (String generationId : journal.removedGenerationIds) removed.add(ClientObjectStore.normalizeHash(generationId));
		if (!List.copyOf(removed).equals(journal.removedGenerationIds)) throw new IOException("Client compaction journal is not canonical");
		deleteJournaledGenerations(removed);
		ClientObjectStore.collectUnreachableObjects(storage, Set.copyOf(generationIds()), Set.of());
		finishCompactionJournal();
	}

	private void writeCompactionJournal(Set<String> removedGenerationIds) throws IOException {
		ClientStorageJsons.ClientCompactionJournalFields journal = new ClientStorageJsons.ClientCompactionJournalFields();
		journal.removedGenerationIds = List.copyOf(new TreeSet<>(removedGenerationIds));
		ConfigTools.writeAtomic(storage.compactionJournalFile(), journal);
	}

	private void deleteJournaledGenerations(Set<String> removedGenerationIds) throws IOException {
		for (String generationId : removedGenerationIds) FileTrees.delete(storage.generationDirectory(generationId));
		removeGeneratedCopies(removedGenerationIds);
	}

	private void finishCompactionJournal() throws IOException {
		Files.deleteIfExists(storage.compactionJournalFile());
		FileTrees.forceDirectory(storage.clientDirectory());
	}

	/**
	 * Returns the newest valid locally stored generation for each installed modpack.
	 *
	 * <p>
	 * Generation records are immutable and may contain several generations of the same pack.
	 * The pack manager needs one safe catalogue entry, so malformed records are ignored and the
	 * newest valid record wins. A missing or damaged record must not prevent the client from opening
	 * the manager for the other packs.
	 * </p>
	 */
	public List<GenerationRecord> installedRecords() throws IOException {
		Map<String, GenerationRecord> newest = new TreeMap<>();
		for (String generationId : generationIds()) {
			GenerationRecord record;
			try {
				record = read(generationId).orElse(null);
			} catch (IOException | RuntimeException ignored) {
				continue;
			}
			if (record == null) continue;
			String modpackId = record.manifest().modpackId();
			GenerationRecord previous = newest.get(modpackId);
			if (previous == null || record.metadata().createdAt().compareTo(previous.metadata().createdAt()) > 0
					|| record.metadata().createdAt().equals(previous.metadata().createdAt())
							&& record.metadata().generationId().compareTo(previous.metadata().generationId()) > 0)
				newest.put(modpackId, record);
		}
		return List.copyOf(newest.values());
	}

	/** Deletes every retained local artifact for one inactive modpack and collects objects no longer referenced by another pack. */
	public void forgetModpack(String modpackId) throws IOException {
		ClientStorageMutation.run(storage, () -> {
			forgetModpackLocked(modpackId);
			return null;
		});
	}

	private void forgetModpackLocked(String modpackId) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		if (Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Cannot forget a modpack while an update transaction is active");
		ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
		if (activeState != null && normalizedModpackId.equals(activeState.modpackId)) throw new IOException("Cannot forget the active modpack");

		List<String> matchingGenerationIds = new ArrayList<>();
		for (String generationId : generationIds()) {
			GenerationRecord record = read(generationId).orElseThrow(() -> new IOException("Client generation record is missing: " + generationId));
			if (normalizedModpackId.equals(record.manifest().modpackId())) matchingGenerationIds.add(generationId);
		}
		for (String generationId : matchingGenerationIds) FileTrees.delete(storage.generationDirectory(generationId));
		FileTrees.delete(storage.generatedCopiesPackDirectory(normalizedModpackId));
		storage.clearOverlay(normalizedModpackId);
		FileTrees.delete(storage.baselineFile(normalizedModpackId).getParent());
		FileTrees.delete(storage.connectionDirectory(normalizedModpackId));
		ClientObjectStore.collectUnreachableObjects(storage, Set.copyOf(generationIds()), Set.of());
	}

	/** Returns the committed lineage ending at the generation selected by active-state.json. */
	public List<GenerationRecord> lineage(String modpackId, String generationId) throws IOException {
		return readLineage(modpackId, generationId, false);
	}

	/** Returns the downloaded part of the committed lineage; skipped server generations are not client records. */
	public List<GenerationRecord> availableLineage(String modpackId, String generationId) throws IOException {
		return readLineage(modpackId, generationId, true);
	}

	private List<GenerationRecord> readLineage(String modpackId, String generationId, boolean stopAtMissingAncestor) throws IOException {
		ModpackId.requireValid(modpackId);
		List<GenerationRecord> reverse = new ArrayList<>();
		Set<String> visited = new HashSet<>();
		String current = generationId;
		while (current != null && !current.isEmpty()) {
			if (!visited.add(current)) throw new IOException("Client generation lineage contains a cycle");
			Optional<GenerationRecord> optional = read(current);
			if (optional.isEmpty()) {
				if (!stopAtMissingAncestor || reverse.isEmpty())
					throw new IOException((stopAtMissingAncestor ? "Active client generation record is missing: " : "Client generation lineage is incomplete: ") + current);
				break;
			}
			GenerationRecord record = optional.get();
			if (!modpackId.equals(record.manifest().modpackId())) throw new IOException("Client generation lineage crosses modpack IDs");
			reverse.add(record);
			current = record.metadata().parentGenerationId();
		}
		Collections.reverse(reverse);
		return List.copyOf(reverse);
	}

	private GenerationSnapshot validateCompactionSnapshot() throws IOException {
		TreeMap<String, GenerationRecord> records = new TreeMap<>();
		for (String generationId : generationIds()) {
			try {
				if (!ClientObjectStore.normalizeHash(generationId).equals(generationId)) throw new IOException("Client generation directory is not canonical: " + generationId);
				GenerationRecord record = read(generationId).orElseThrow(() -> new IOException("Client generation record is missing: " + generationId));
				if (!generationId.equals(record.metadata().generationId())) throw new IOException("Client generation directory and record IDs disagree: " + generationId);
				records.put(generationId, record);
			} catch (RuntimeException e) {
				throw new IOException("Client generation record is invalid: " + generationId, e);
			}
		}

		TreeMap<String, String> newest = new TreeMap<>();
		for (Map.Entry<String, GenerationRecord> entry : records.entrySet()) {
			String modpackId = entry.getValue().manifest().modpackId();
			String previousId = newest.get(modpackId);
			if (previousId == null || newer(entry.getValue(), records.get(previousId))) newest.put(modpackId, entry.getKey());
		}
		Set<String> retained = new HashSet<>(newest.values());
		ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
		if (activeState != null) {
			GenerationRecord active = records.get(activeState.generationId);
			if (active == null) throw new IOException("Active client generation record is missing: " + activeState.generationId);
			if (!active.manifest().modpackId().equals(activeState.modpackId)) throw new IOException("Active client generation identity is inconsistent");
			retained.add(activeState.generationId);
		}
		Set<String> removed = new TreeSet<>(records.keySet());
		removed.removeAll(retained);
		return new GenerationSnapshot(Map.copyOf(records), Set.copyOf(new TreeSet<>(retained)), Set.copyOf(removed));
	}

	private void removeGeneratedCopies(Set<String> removedGenerationIds) throws IOException {
		if (removedGenerationIds.isEmpty() || !Files.exists(storage.generatedCopiesDirectory(), LinkOption.NOFOLLOW_LINKS)) return;
		try (Stream<Path> packs = Files.list(storage.generatedCopiesDirectory())) {
			for (Path pack : packs.sorted().toList()) {
				if (Files.isSymbolicLink(pack) || !Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generated-copy state contains an unsupported entry: " + pack);
				ModpackId.requireValid(pack.getFileName().toString());
				try (Stream<Path> generations = Files.list(pack)) {
					for (Path generation : generations.sorted().toList()) {
						if (Files.isSymbolicLink(generation) || !Files.isDirectory(generation, LinkOption.NOFOLLOW_LINKS))
							throw new IOException("Client generated-copy state contains an unsupported entry: " + generation);
						String generationId = generation.getFileName().toString();
						if (removedGenerationIds.contains(generationId)) FileTrees.delete(storage.generatedCopiesGenerationDirectory(pack.getFileName().toString(), generationId));
					}
				}
			}
		}
	}

	private FileTotals generationTotals(Set<String> generationIds) throws IOException {
		long bytes = 0;
		for (String generationId : generationIds) {
			Path directory = storage.generationDirectory(generationId);
			if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generation directory is missing: " + generationId);
			try (Stream<Path> paths = Files.walk(directory)) {
				for (Path path : paths.filter(candidate -> !candidate.equals(directory)).toList()) {
					if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
					bytes = Math.addExact(bytes, Files.size(path));
				}
			}
		}
		return new FileTotals(generationIds.size(), bytes);
	}

	private static boolean newer(GenerationRecord candidate, GenerationRecord current) {
		return candidate.metadata().createdAt().compareTo(current.metadata().createdAt()) > 0
				|| candidate.metadata().createdAt().equals(current.metadata().createdAt())
						&& candidate.metadata().generationId().compareTo(current.metadata().generationId()) > 0;
	}

	private static Optional<ModpackJsons.CompleteModpackContentFields> readFields(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generation manifest is not a regular file: " + path);
		try {
			return ConfigTools.read(path, ModpackJsons.CompleteModpackContentFields.class).map(fields -> {
				GenerationRecord.fromFields(fields);
				GenerationPatchNoteHistory.fromFields(fields);
				return fields;
			});
		} catch (RuntimeException e) {
			throw new IOException("Client generation manifest is invalid: " + path, e);
		}
	}

	private static void verify(Path path, GenerationRecord record, List<GenerationPatchNoteHistory.Entry> patchNotesHistory, GenerationHistoryIndex historyIndex) throws IOException {
		ModpackJsons.CompleteModpackContentFields fields = readFields(path).orElseThrow(() -> new IOException("Stored client generation could not be verified: " + path));
		if (!GenerationRecord.fromFields(fields).equals(record)) throw new IOException("Stored client generation verification failed: " + path);
		if (!GenerationPatchNoteHistory.fromFields(fields).equals(patchNotesHistory)) throw new IOException("Stored client patch-note history verification failed: " + path);
		GenerationHistoryIndex actualHistoryIndex = fields.generationHistory == null ? null : GenerationHistoryIndex.fromFields(fields.generationHistory);
		if (!Objects.equals(actualHistoryIndex, historyIndex)) throw new IOException("Stored client generation history index verification failed: " + path);
	}
}
