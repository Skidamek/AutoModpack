package pl.skidam.automodpack_core.modpack.generation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.storage.ObjectStoreMaintenance;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.ImmutableFiles;

/**
 * The compaction engine behind {@link GenerationStore}: preview receipts, validated deletion passes,
 * and checkpoint-driven recovery of interrupted compaction. All methods expect the owning store's
 * publication guard to be held by the caller.
 */
public final class GenerationCompactor {

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

	private final GenerationStore store;

	GenerationCompactor(GenerationStore store) {
		this.store = store;
	}

	CompactionPreview previewCompactionLocked(String boundaryGenerationId) throws IOException {
		GenerationHistoryIndex index = store.currentHistoryIndexLocked().orElseThrow(() -> new IOException("Cannot compact without a valid current generation"));
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

	CompactionResult compactBeforeLocked(String boundaryGenerationId) throws IOException {
		GenerationCheckpoint pending = store.readCheckpoint().filter(checkpoint -> !checkpoint.supersededGenerationIds().isEmpty() || !checkpoint.supersededCatalogueStateDigests().isEmpty()).orElse(null);
		if (pending != null) {
			CompactionCleanup cleanup = finishCompactionLocked(pending);
			if (pending.boundaryGenerationId().equals(boundaryGenerationId))
				return new CompactionResult(boundaryGenerationId, List.copyOf(pending.supersededGenerationIds()), List.copyOf(pending.supersededCatalogueStateDigests()),
						cleanup.catalogues().count(), cleanup.commits().count(), cleanup.deltas().count(), cleanup.catalogues().bytes(), cleanup.commits().bytes(), cleanup.deltas().bytes());
		}
		Optional<GenerationStore.CurrentSnapshot> current = store.loadCurrentState(true, false);
		if (current.isEmpty()) throw new IOException("Cannot compact without a valid current generation");
		GenerationRecord currentRecord = current.orElseThrow().record();
		GenerationHistoryIndex fullIndex = store.historyIndex(currentRecord.metadata().generationId());
		CompactionPreview preview = previewCompactionLocked(boundaryGenerationId);
		if (preview.supersededGenerationIds().isEmpty()) return new CompactionResult(boundaryGenerationId, preview.supersededGenerationIds(), preview.supersededCatalogueStateDigests(), 0, 0, 0, 0, 0, 0);
		GenerationStore.CompactHistory history = store.readCompactHistory(currentRecord.metadata().generationId());
		GenerationHistoryEntry boundaryEntry = history.entries().stream().filter(entry -> entry.metadata().generationId().equals(boundaryGenerationId)).findFirst().orElse(null);
		if (boundaryEntry == null) throw new IOException("Compaction boundary details are no longer available: " + boundaryGenerationId);
		GenerationRecord boundaryRecord = store.readCompactRecord(boundaryGenerationId);
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
		ConfigTools.writeAtomic(store.checkpointPath(), checkpoint.toFields());
		GenerationCheckpoint verifiedCheckpoint = store.readCheckpoint().orElseThrow(() -> new IOException("Generation checkpoint disappeared after publication"));
		if (!verifiedCheckpoint.equals(checkpoint) || !verifiedCheckpoint.record().equals(boundaryRecord))
			throw new IOException("Generation checkpoint does not match the retained compaction boundary");

		CompactionCleanup cleanup = finishCompactionLocked(checkpoint);
		return new CompactionResult(boundaryGenerationId, preview.supersededGenerationIds(), preview.supersededCatalogueStateDigests(), cleanup.catalogues().count(), cleanup.commits().count(),
				cleanup.deltas().count(), cleanup.catalogues().bytes(), cleanup.commits().bytes(), cleanup.deltas().bytes());
	}

	void recoverCompactionLocked() throws IOException {
		GenerationCheckpoint checkpoint = store.readCheckpoint().orElse(null);
		if (checkpoint == null || checkpoint.supersededGenerationIds().isEmpty() && checkpoint.supersededCatalogueStateDigests().isEmpty()) return;
		finishCompactionLocked(checkpoint);
	}

	void validateCheckpointBoundaryFiles(GenerationCheckpoint checkpoint) throws IOException {
		GenerationRecord record = checkpoint.record();
		Path commitFile = store.commitPath(checkpoint.boundaryGenerationId());
		if (Files.exists(commitFile, LinkOption.NOFOLLOW_LINKS)) {
			GenerationCommit commit = store.readCommit(commitFile);
			if (!commit.metadata().equals(record.metadata()) || !commit.modpackId().equals(record.manifest().modpackId()))
				throw new IOException("Generation checkpoint does not match its boundary commit: " + commitFile);
			Path catalogueFile = store.cataloguePath(record.metadata().stateDigest());
			if (Files.exists(catalogueFile, LinkOption.NOFOLLOW_LINKS) && !store.readCatalogue(catalogueFile).manifest().equals(record.manifest()))
				throw new IOException("Generation checkpoint does not match its boundary catalogue: " + catalogueFile);
			Path deltaFile = store.deltaPath(checkpoint.boundaryGenerationId());
			if (Files.exists(deltaFile, LinkOption.NOFOLLOW_LINKS)) {
				OwnershipDelta delta = store.readDelta(deltaFile);
				if (!delta.modpackId().equals(record.manifest().modpackId()) || !delta.digest().equals(commit.ownershipDeltaDigest()))
					throw new IOException("Generation checkpoint does not match its boundary ownership delta: " + deltaFile);
			}
		} else {
			Path catalogueFile = store.cataloguePath(record.metadata().stateDigest());
			if (Files.exists(catalogueFile, LinkOption.NOFOLLOW_LINKS) && !store.readCatalogue(catalogueFile).manifest().equals(record.manifest()))
				throw new IOException("Generation checkpoint does not match its boundary catalogue: " + catalogueFile);
		}
	}

	private CompactionCleanup finishCompactionLocked(GenerationCheckpoint checkpoint) throws IOException {
		GenerationJsons.GenerationPointerFields pointer = store.readCurrentPointer();
		GenerationRecord currentRecord = store.readCompactRecord(pointer.generationId);
		store.writeCurrentProjection(currentRecord);
		DeletionResult catalogues = deleteCompactionFiles(checkpoint.supersededCatalogueStateDigests().stream().map(this::cataloguePathUnchecked).toList(), "generation catalogue");
		DeletionResult commits = deleteCompactionFiles(checkpoint.supersededGenerationIds().stream().map(this::commitPathUnchecked).toList(), "generation commit");
		DeletionResult deltas = deleteCompactionFiles(checkpoint.supersededGenerationIds().stream().map(this::deltaPathUnchecked).toList(), "generation ownership delta");
		store.publishCurrentOwnership();
		GenerationCheckpoint completed = new GenerationCheckpoint(checkpoint.record(), checkpoint.patchNotesHistory(), checkpoint.historyIndex(), Set.of(), Set.of());
		ConfigTools.writeAtomic(store.checkpointPath(), completed.toFields());
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
			store.compactionDeleteHook().beforeDelete(path);
			if (ImmutableFiles.deleteIfExists(path)) {
				deleted = ObjectStoreMaintenance.addExact(deleted, 1, "deleted " + description + " count");
				bytes = ObjectStoreMaintenance.addExact(bytes, size, "deleted " + description + " bytes");
			}
		}
		if (deleted > 0 && !paths.isEmpty()) FileTrees.forceDirectory(paths.get(0).getParent());
		return new DeletionResult(deleted, bytes);
	}

	private long reclaimableBytes(List<Path> paths) throws IOException {
		long bytes = 0;
		for (Path path : paths) if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) bytes = ObjectStoreMaintenance.addExact(bytes, Files.size(path), "compaction reclaimable bytes");
		return bytes;
	}

	private Path cataloguePathUnchecked(String stateDigest) {
		return store.cataloguesDirectory().resolve(stateDigest + ".json");
	}

	private Path commitPathUnchecked(String generationId) {
		return store.commitsDirectory().resolve(generationId + ".json");
	}

	private Path deltaPathUnchecked(String generationId) {
		return store.deltasDirectory().resolve(generationId + ".json");
	}

	private record DeletionResult(long count, long bytes) {}

	private record CompactionCleanup(DeletionResult catalogues, DeletionResult commits, DeletionResult deltas) {}
}
