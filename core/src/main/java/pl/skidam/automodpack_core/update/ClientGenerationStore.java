package pl.skidam.automodpack_core.update;

import java.io.IOException;
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
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * Persistent client snapshots of downloaded pack documents, keyed by content token. Snapshots keep
 * local history, switching, and offline repair working without server access.
 */
public final class ClientGenerationStore {
	private final ClientStorage storage;

	/** A deterministic receipt for one explicit client storage compaction pass. */
	public record CompactionResult(
			List<String> retainedTokens,
			List<String> removedTokens,
			long recordCountBefore,
			long recordBytesBefore,
			long recordCountAfter,
			long recordBytesAfter,
			long generatedCopyCountBefore,
			long generatedCopyBytesBefore,
			long generatedCopyCountAfter,
			long generatedCopyBytesAfter,
			ClientObjectStore.CollectionResult objectCollection) {
		public CompactionResult {
			retainedTokens = sortedTokens(retainedTokens, "retained content tokens");
			removedTokens = sortedTokens(removedTokens, "removed content tokens");
			if (List.of(recordCountBefore, recordBytesBefore, recordCountAfter, recordBytesAfter, generatedCopyCountBefore, generatedCopyBytesBefore, generatedCopyCountAfter,
					generatedCopyBytesAfter).stream().anyMatch(value -> value < 0))
				throw new IllegalArgumentException("Compaction receipt values cannot be negative");
			if (!Collections.disjoint(retainedTokens, removedTokens)) throw new IllegalArgumentException("Compaction retained and removed tokens overlap");
			objectCollection = Objects.requireNonNull(objectCollection, "object collection receipt");
		}

		private static List<String> sortedTokens(List<String> tokens, String description) {
			Objects.requireNonNull(tokens, description);
			return tokens.stream().map(token -> {
				try {
					return ClientObjectStore.normalizeHash(token);
				} catch (IllegalArgumentException e) {
					throw new IllegalArgumentException("Invalid " + description + ": " + token, e);
				}
			}).distinct().sorted().toList();
		}
	}

	private record Snapshot(Map<String, PackDocument> records, Set<String> retainedTokens, Set<String> removedTokens) {}
	private record FileTotals(long count, long bytes) {}

	public ClientGenerationStore(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage);
	}

	/** Persists one downloaded pack document with its journal tail. */
	public void write(PackDocument document, List<JournalEntry> journal) throws IOException {
		ClientStorageMutation.run(storage, () -> {
			writeLocked(document, journal);
			return null;
		});
	}

	private void writeLocked(PackDocument document, List<JournalEntry> journal) throws IOException {
		Objects.requireNonNull(document, "document");
		Objects.requireNonNull(journal, "journal");
		Path path = storage.generationManifest(document.contentToken());
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			HeadDocumentSnapshot existing = readSnapshot(path).orElseThrow(() -> new IOException("Stored client generation is invalid: " + path));
			if (!existing.document().equals(document)) throw new IOException("Client generation record already exists with different content: " + path);
			if (!existing.journal().equals(journal)) {
				ConfigTools.writeAtomic(path, toFields(document, journal));
				verify(path, document, journal);
			}
			return;
		}
		Files.createDirectories(path.getParent());
		ConfigTools.writeAtomic(path, toFields(document, journal));
		verify(path, document, journal);
	}

	private static GenerationJsons.HeadDocumentFields toFields(PackDocument document, List<JournalEntry> journal) {
		GenerationJsons.HeadDocumentFields fields = new GenerationJsons.HeadDocumentFields();
		fields.contentToken = document.contentToken();
		fields.policySha1 = document.policySha1();
		fields.createdAt = document.createdAt().toString();
		fields.journalHead = journal.isEmpty() ? 0 : journal.get(journal.size() - 1).seq();
		fields.journal = journal.stream().map(JournalEntry::toFields).toList();
		fields.ownershipLedger = document.ownershipLedger().toFields();
		fields.policy = document.manifest().toFields();
		return fields;
	}

	public Optional<PackDocument> read(String contentToken) throws IOException {
		return readSnapshot(storage.generationManifest(contentToken)).map(HeadDocumentSnapshot::document);
	}

	public Optional<HeadDocumentSnapshot> readSnapshot(String contentToken) throws IOException {
		return readSnapshot(storage.generationManifest(contentToken));
	}

	public List<JournalEntry> journal(String contentToken) throws IOException {
		return readSnapshot(storage.generationManifest(contentToken)).map(HeadDocumentSnapshot::journal).orElse(List.of());
	}

	public Optional<GenerationJsons.HeadDocumentFields> readFields(String contentToken) throws IOException {
		return readSnapshot(storage.generationManifest(contentToken)).map(HeadDocumentSnapshot::fields);
	}

	/** Reconstructs the active target from one validated pack document and the persisted selection intent. */
	public Optional<SelectedModpackTarget> readActiveTarget(ClientPlatform platform) throws IOException {
		Objects.requireNonNull(platform, "platform");
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state == null) return Optional.empty();
		HeadDocumentSnapshot snapshot = readSnapshot(state.contentToken)
				.orElseThrow(() -> new IOException("Active client generation record is missing: " + state.contentToken));
		if (!Objects.equals(state.modpackId, snapshot.document().manifest().modpackId()))
			throw new IOException("Active client state and generation record belong to different modpacks");
		Optional<SelectionIntent> stored = new ClientSelectionStore(storage.selectionFile()).get(state.modpackId);
		return stored.isPresent()
				? Optional.of(SelectedModpackTarget.prepare(snapshot.fields(), null, stored.get(), platform))
				: Optional.of(SelectedModpackTarget.prepareDefault(snapshot.fields(), platform));
	}

	private List<String> tokens() throws IOException {
		if (!Files.exists(storage.recordsDirectory(), LinkOption.NOFOLLOW_LINKS)) return List.of();
		try (Stream<Path> paths = Files.list(storage.recordsDirectory())) {
			return paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	public CompactionResult compact() throws IOException {
		return ClientStorageMutation.run(storage, this::compactLocked);
	}

	private CompactionResult compactLocked() throws IOException {
		recoverCompactionLocked();
		if (Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Cannot compact client storage while an update transaction is active: " + storage.transactionFile());
		Snapshot snapshot = validateCompactionSnapshot();
		ClientObjectStore.validate(storage);
		FileTotals recordsBefore = recordTotals(snapshot.records().keySet());
		ClientObjectStore.GeneratedCopyReport generatedBefore = ClientObjectStore.measureGeneratedCopies(storage);

		writeCompactionJournal(snapshot.removedTokens());
		deleteJournaledRecords(snapshot.removedTokens());

		ClientObjectStore.CollectionResult objectCollection = ClientObjectStore.collectUnreachableObjects(storage, snapshot.retainedTokens(), Set.of());
		finishCompactionJournal();
		try (FileCache metadata = FileCache.open(storage.fileCacheDirectory())) {
			metadata.cleanup();
		}
		FileTotals recordsAfter = recordTotals(snapshot.retainedTokens());
		ClientObjectStore.GeneratedCopyReport generatedAfter = ClientObjectStore.measureGeneratedCopies(storage);
		return new CompactionResult(List.copyOf(snapshot.retainedTokens()), List.copyOf(snapshot.removedTokens()), recordsBefore.count(), recordsBefore.bytes(),
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
		for (String token : journal.removedGenerationIds) removed.add(ClientObjectStore.normalizeHash(token));
		if (!List.copyOf(removed).equals(journal.removedGenerationIds)) throw new IOException("Client compaction journal is not canonical");
		deleteJournaledRecords(removed);
		ClientObjectStore.collectUnreachableObjects(storage, Set.copyOf(tokens()), Set.of());
		finishCompactionJournal();
	}

	private void writeCompactionJournal(Set<String> removedTokens) throws IOException {
		ClientStorageJsons.ClientCompactionJournalFields journal = new ClientStorageJsons.ClientCompactionJournalFields();
		journal.removedGenerationIds = List.copyOf(new TreeSet<>(removedTokens));
		ConfigTools.writeAtomic(storage.compactionJournalFile(), journal);
	}

	private void deleteJournaledRecords(Set<String> removedTokens) throws IOException {
		for (String token : removedTokens) FileTrees.delete(storage.generationDirectory(token));
		removeGeneratedCopies(removedTokens);
	}

	private void finishCompactionJournal() throws IOException {
		Files.deleteIfExists(storage.compactionJournalFile());
		FileTrees.forceDirectory(storage.clientDirectory());
	}

	/** Returns the newest valid locally stored generation for each installed modpack. */
	public List<PackDocument> installedRecords() throws IOException {
		Map<String, PackDocument> newest = new TreeMap<>();
		for (String token : tokens()) {
			PackDocument record;
			try {
				record = read(token).orElse(null);
			} catch (IOException | RuntimeException ignored) {
				continue;
			}
			if (record == null) continue;
			String modpackId = record.manifest().modpackId();
			PackDocument previous = newest.get(modpackId);
			if (previous == null || newer(record, previous)) newest.put(modpackId, record);
		}
		return List.copyOf(newest.values());
	}

	/** Returns the newest installed record for one modpack, or empty when that modpack is not installed. */
	public Optional<PackDocument> installedRecord(String modpackId) throws IOException {
		String normalizedModpackId = ModpackId.requireValid(modpackId);
		for (PackDocument record : installedRecords())
			if (normalizedModpackId.equals(record.manifest().modpackId())) return Optional.of(record);
		return Optional.empty();
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

		List<String> matchingTokens = new ArrayList<>();
		for (String token : tokens()) {
			PackDocument record = read(token).orElseThrow(() -> new IOException("Client generation record is missing: " + token));
			if (normalizedModpackId.equals(record.manifest().modpackId())) matchingTokens.add(token);
		}
		ClientSelectionStore selections = new ClientSelectionStore(storage.selectionFile());
		SelectionIntent expectedSelection = selections.get(normalizedModpackId).orElse(null);
		selections.remove(normalizedModpackId, expectedSelection);
		for (String token : matchingTokens) FileTrees.delete(storage.generationDirectory(token));
		FileTrees.delete(storage.generatedCopiesPackDirectory(normalizedModpackId));
		storage.clearOverlay(normalizedModpackId);
		FileTrees.delete(storage.baselineFile(normalizedModpackId).getParent());
		FileTrees.delete(storage.connectionDirectory(normalizedModpackId));
		ClientObjectStore.collectUnreachableObjects(storage, Set.copyOf(tokens()), Set.of());
	}

	private Snapshot validateCompactionSnapshot() throws IOException {
		TreeMap<String, PackDocument> records = new TreeMap<>();
		for (String token : tokens()) {
			try {
				if (!ClientObjectStore.normalizeHash(token).equals(token)) throw new IOException("Client generation directory is not canonical: " + token);
				PackDocument record = read(token).orElseThrow(() -> new IOException("Client generation record is missing: " + token));
				if (!token.equals(record.contentToken())) throw new IOException("Client generation directory and record tokens disagree: " + token);
				records.put(token, record);
			} catch (RuntimeException e) {
				throw new IOException("Client generation record is invalid: " + token, e);
			}
		}

		TreeMap<String, String> newest = new TreeMap<>();
		for (Map.Entry<String, PackDocument> entry : records.entrySet()) {
			String modpackId = entry.getValue().manifest().modpackId();
			String previousToken = newest.get(modpackId);
			if (previousToken == null || newer(entry.getValue(), records.get(previousToken))) newest.put(modpackId, entry.getKey());
		}
		Set<String> retained = new HashSet<>(newest.values());
		ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
		if (activeState != null) {
			PackDocument active = records.get(activeState.contentToken);
			if (active == null) throw new IOException("Active client generation record is missing: " + activeState.contentToken);
			if (!active.manifest().modpackId().equals(activeState.modpackId)) throw new IOException("Active client generation identity is inconsistent");
			retained.add(activeState.contentToken);
		}
		Set<String> removed = new TreeSet<>(records.keySet());
		removed.removeAll(retained);
		return new Snapshot(Map.copyOf(records), Set.copyOf(new TreeSet<>(retained)), Set.copyOf(removed));
	}

	private void removeGeneratedCopies(Set<String> removedTokens) throws IOException {
		if (removedTokens.isEmpty() || !Files.exists(storage.generatedCopiesDirectory(), LinkOption.NOFOLLOW_LINKS)) return;
		try (Stream<Path> packs = Files.list(storage.generatedCopiesDirectory())) {
			for (Path pack : packs.sorted().toList()) {
				if (Files.isSymbolicLink(pack) || !Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generated-copy state contains an unsupported entry: " + pack);
				ModpackId.requireValid(pack.getFileName().toString());
				try (Stream<Path> generations = Files.list(pack)) {
					for (Path generation : generations.sorted().toList()) {
						if (Files.isSymbolicLink(generation) || !Files.isDirectory(generation, LinkOption.NOFOLLOW_LINKS))
							throw new IOException("Client generated-copy state contains an unsupported entry: " + generation);
						String token = generation.getFileName().toString();
						if (removedTokens.contains(token)) FileTrees.delete(storage.generatedCopiesGenerationDirectory(pack.getFileName().toString(), token));
					}
				}
			}
		}
	}

	private FileTotals recordTotals(Set<String> records) throws IOException {
		long bytes = 0;
		for (String token : records) {
			Path directory = storage.generationDirectory(token);
			if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generation directory is missing: " + token);
			try (Stream<Path> paths = Files.walk(directory)) {
				for (Path path : paths.filter(candidate -> !candidate.equals(directory)).toList()) {
					if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
					bytes = Math.addExact(bytes, Files.size(path));
				}
			}
		}
		return new FileTotals(records.size(), bytes);
	}

	private static boolean newer(PackDocument candidate, PackDocument current) {
		return candidate.createdAt().compareTo(current.createdAt()) > 0
				|| candidate.createdAt().equals(current.createdAt()) && candidate.contentToken().compareTo(current.contentToken()) > 0;
	}

	private record HeadDocumentSnapshot(PackDocument document, List<JournalEntry> journal, GenerationJsons.HeadDocumentFields fields) {}

	private static Optional<HeadDocumentSnapshot> readSnapshot(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client generation manifest is not a regular file: " + path);
		try {
			GenerationJsons.HeadDocumentFields fields = ConfigTools.read(path, GenerationJsons.HeadDocumentFields.class).orElse(null);
			if (fields == null) return Optional.empty();
			PackDocument document = PackDocument.fromFields(fields);
			List<JournalEntry> journal = new ArrayList<>();
			for (GenerationJsons.JournalEntryFields entry : fields.journal == null ? List.<GenerationJsons.JournalEntryFields>of() : fields.journal)
				journal.add(JournalEntry.fromFields(entry));
			return Optional.of(new HeadDocumentSnapshot(document, List.copyOf(journal), fields));
		} catch (RuntimeException e) {
			throw new IOException("Client generation manifest is invalid: " + path, e);
		}
	}

	private static void verify(Path path, PackDocument document, List<JournalEntry> journal) throws IOException {
		HeadDocumentSnapshot stored = readSnapshot(path).orElseThrow(() -> new IOException("Stored client generation could not be verified: " + path));
		if (!stored.document().equals(document)) throw new IOException("Stored client generation verification failed: " + path);
		if (!stored.journal().equals(journal)) throw new IOException("Stored client journal verification failed: " + path);
	}
}
