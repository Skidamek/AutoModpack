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
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.cache.ClientObjectStore;

/** Persistent immutable client copies of complete server generation records. */
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
		write(record, GenerationPatchNoteHistory.forRecord(record));
	}

	public void write(GenerationRecord record, List<GenerationPatchNoteHistory.Entry> patchNotesHistory) throws IOException {
		Objects.requireNonNull(record, "record");
		Objects.requireNonNull(patchNotesHistory, "patchNotesHistory");
		storage.ensureRoots();
		Path path = storage.generationManifest(record.metadata().generationId());
		ModpackJsons.CompleteModpackContentFields fields = record.toFields();
		GenerationPatchNoteHistory.writeFields(fields, patchNotesHistory);
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			ModpackJsons.CompleteModpackContentFields existingFields = readFields(path).orElseThrow(() -> new IOException("Stored client generation is invalid: " + path));
			GenerationRecord existing = GenerationRecord.fromFields(existingFields);
			if (!existing.equals(record)) throw new IOException("Client generation record already exists with different content: " + path);
			if (!GenerationPatchNoteHistory.fromFields(existingFields).equals(patchNotesHistory)) {
				if (existingFields.patchNotesHistory != null && !existingFields.patchNotesHistory.isEmpty())
					throw new IOException("Client generation patch-note history already exists with different content: " + path);
				ConfigTools.writeAtomic(path, fields);
				verify(path, record, patchNotesHistory);
			}
			return;
		}
		Files.createDirectories(path.getParent());
		ConfigTools.writeAtomic(path, fields);
		verify(path, record, patchNotesHistory);
	}

	public Optional<GenerationRecord> read(String generationId) throws IOException {
		return readFields(generationId).map(GenerationRecord::fromFields);
	}

	public Optional<ModpackJsons.CompleteModpackContentFields> readFields(String generationId) throws IOException {
		return readFields(storage.generationManifest(generationId));
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
		if (Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Cannot compact client storage while an update transaction is active: " + storage.transactionFile());
		GenerationSnapshot snapshot = validateCompactionSnapshot();
		ClientObjectStore.validate(storage);
		FileTotals recordsBefore = generationTotals(snapshot.records().keySet());
		ClientObjectStore.GeneratedCopyReport generatedBefore = ClientObjectStore.measureGeneratedCopies(storage);

		for (String generationId : snapshot.removedGenerationIds()) SmartFileUtils.deleteTree(storage.generationDirectory(generationId));
		removeGeneratedCopies(snapshot.removedGenerationIds());

		ClientObjectStore.CollectionResult objectCollection = ClientObjectStore.collectUnreachableObjects(storage, snapshot.retainedGenerationIds(), Set.of());
		FileTotals recordsAfter = generationTotals(snapshot.retainedGenerationIds());
		ClientObjectStore.GeneratedCopyReport generatedAfter = ClientObjectStore.measureGeneratedCopies(storage);
		return new CompactionResult(List.copyOf(snapshot.retainedGenerationIds()), List.copyOf(snapshot.removedGenerationIds()), recordsBefore.count(), recordsBefore.bytes(),
				recordsAfter.count(), recordsAfter.bytes(), generatedBefore.count(), generatedBefore.bytes(), generatedAfter.count(), generatedAfter.bytes(), objectCollection);
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
						if (removedGenerationIds.contains(generationId)) SmartFileUtils.deleteTree(storage.generatedCopiesGenerationDirectory(pack.getFileName().toString(), generationId));
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

	private static void verify(Path path, GenerationRecord record, List<GenerationPatchNoteHistory.Entry> patchNotesHistory) throws IOException {
		ModpackJsons.CompleteModpackContentFields fields = readFields(path).orElseThrow(() -> new IOException("Stored client generation could not be verified: " + path));
		if (!GenerationRecord.fromFields(fields).equals(record)) throw new IOException("Stored client generation verification failed: " + path);
		if (!GenerationPatchNoteHistory.fromFields(fields).equals(patchNotesHistory)) throw new IOException("Stored client patch-note history verification failed: " + path);
	}
}
