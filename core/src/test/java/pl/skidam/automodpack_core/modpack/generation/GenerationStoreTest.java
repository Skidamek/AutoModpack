package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.StagedObject;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ImmutableFiles;

class GenerationStoreTest {
	@TempDir
	Path tempDir;

	@Test
	void publishesRootThenParentAndReturnsNoChanges() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "First generation notes\r\n");
		assertEquals(GenerationStore.PublicationStatus.PUBLISHED, first.status());
		assertEquals("", first.record().metadata().parentGenerationId());
		Path catalogue = tempDir.resolve("catalogues").resolve(first.record().metadata().stateDigest() + ".json");
		Path commit = tempDir.resolve("commits").resolve(first.record().metadata().generationId() + ".json");
		assertTrue(Files.exists(catalogue));
		assertTrue(Files.exists(commit));
		assertTrue(ImmutableFiles.isProtected(catalogue));
		assertTrue(ImmutableFiles.isProtected(commit));
		assertFalse(Files.exists(tempDir.resolve("records")));
		String pointer = Files.readString(tempDir.resolve("current.json"), StandardCharsets.UTF_8);

		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();
		GenerationStore.Publication unchanged = store.publish(candidate("first"), Optional.of(current), "First generation notes\n");
		assertEquals(GenerationStore.PublicationStatus.NO_CHANGES, unchanged.status());
		assertEquals(pointer, Files.readString(tempDir.resolve("current.json"), StandardCharsets.UTF_8));
		assertFalse(Files.exists(tempDir.resolve("records")));

		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(current), "");
		assertEquals(GenerationStore.PublicationStatus.PUBLISHED, second.status());
		assertEquals(first.record().metadata().generationId(), second.record().metadata().parentGenerationId());
		assertEquals(second.record(), store.loadCurrent().orElseThrow().record());
	}

	@Test
	void publishesMetadataOnlyGenerationForChangedPatchNotes() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "First generation notes");
		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();

		GenerationStore.Publication changedNotes = store.publish(candidate("first"), Optional.of(current), "Updated generation notes");
		assertEquals(GenerationStore.PublicationStatus.PUBLISHED, changedNotes.status());
		assertNotEquals(first.record().metadata().generationId(), changedNotes.record().metadata().generationId());
		assertEquals(first.record().metadata().generationId(), changedNotes.record().metadata().parentGenerationId());
		assertEquals(first.record().metadata().stateDigest(), changedNotes.record().metadata().stateDigest());
		assertEquals(first.record().metadata().ledgerDigest(), changedNotes.record().metadata().ledgerDigest());
		assertEquals("Updated generation notes", changedNotes.record().metadata().patchNotes());
		assertEquals(changedNotes.record(), store.loadCurrent().orElseThrow().record());

		List<GenerationHistoryEntry> history = store.currentHistory();
		assertEquals(List.of(first.record().metadata().generationId(), changedNotes.record().metadata().generationId()), history.stream()
				.map(entry -> entry.metadata().generationId()).toList());
		assertEquals(List.of("First generation notes", "Updated generation notes"), history.stream().map(entry -> entry.metadata().patchNotes()).toList());
		ModpackJsons.CompleteModpackContentFields fields = ConfigTools.read(tempDir.resolve("current-projection.json"), ModpackJsons.CompleteModpackContentFields.class).orElseThrow();
		assertEquals(List.of("First generation notes", "Updated generation notes"), GenerationPatchNoteHistory.fromFields(fields).stream()
				.map(GenerationPatchNoteHistory.Entry::patchNotes).toList());
		assertEquals(List.of(first.record().metadata().generationId(), changedNotes.record().metadata().generationId()),
				GenerationHistoryIndex.fromFields(fields.generationHistory).entries().stream().map(GenerationHistoryIndex.Entry::generationId).toList());
	}

	@Test
	void currentProjectionCarriesPatchNotesForSkippedGenerations() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "First generation notes");
		GenerationStore.CurrentSnapshot firstCurrent = store.loadCurrent().orElseThrow();
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(firstCurrent), "Second generation notes");

		ModpackJsons.CompleteModpackContentFields fields = ConfigTools.read(tempDir.resolve("current-projection.json"), ModpackJsons.CompleteModpackContentFields.class).orElseThrow();
		List<GenerationPatchNoteHistory.Entry> history = GenerationPatchNoteHistory.fromFields(fields);
		assertEquals(List.of("First generation notes", "Second generation notes"), history.stream().map(GenerationPatchNoteHistory.Entry::patchNotes).toList());
		assertEquals(List.of("Second generation notes"), GenerationPatchNoteHistory.after(history, first.record().metadata().generationId()).stream()
				.map(GenerationPatchNoteHistory.Entry::patchNotes).toList());
		assertEquals(second.record().metadata().generationId(), history.get(history.size() - 1).generationId());
	}

	@Test
	void concurrentPublishersFromOneParentCannotBothSucceed() throws Exception {
		Path firstRoot = tempDir.toAbsolutePath().normalize();
		Path equalRoot = Path.of(firstRoot.toString());
		assertEquals(firstRoot, equalRoot);
		assertNotSame(firstRoot, equalRoot);
		GenerationStore firstStore = new GenerationStore(firstRoot, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), () -> {});
		firstStore.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot parent = firstStore.loadCurrent().orElseThrow();
		GenerationStore secondStore = new GenerationStore(equalRoot, Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC), () -> {});
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> firstStore.publish(candidate("second"), Optional.of(parent), ""));
			var second = executor.submit(() -> secondStore.publish(candidate("third"), Optional.of(parent), ""));
			int successes = 0;
			int failures = 0;
			for (var result : List.of(first, second)) {
				try {
					assertEquals(GenerationStore.PublicationStatus.PUBLISHED, result.get().status());
					successes++;
				} catch (ExecutionException e) {
					assertInstanceOf(IOException.class, e.getCause());
					failures++;
				}
			}
			assertEquals(1, successes);
			assertEquals(1, failures);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void pointerFailureLeavesOldCurrentAuthoritative() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		byte[] oldPointer = Files.readAllBytes(tempDir.resolve("current.json"));
		GenerationStore failing = new GenerationStore(tempDir, Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC),
				() -> { throw new IOException("injected pointer failure"); });
		GenerationStore.CurrentSnapshot current = failing.loadCurrent().orElseThrow();
		assertThrows(IOException.class, () -> failing.publish(candidate("second"), Optional.of(current), ""));
		assertArrayEquals(oldPointer, Files.readAllBytes(tempDir.resolve("current.json")));
		assertEquals(first.record(), failing.loadCurrent().orElseThrow().record());
		assertFalse(Files.exists(tempDir.resolve("records")));
	}

	@Test
	void missingCurrentNeverGuessesAnOrphanRecord() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication publication = store.publish(candidate("first"), Optional.empty(), "");
		Files.delete(tempDir.resolve("current.json"));
		assertTrue(store.loadCurrent().isEmpty());
		assertTrue(Files.exists(publication.projectionPath()));
	}

	@Test
	void currentLoadRebuildsFromCompactHistoryWhenProjectionIsMissing() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication publication = store.publish(candidate("first"), Optional.empty(), "");
		Path projection = tempDir.resolve("current-projection.json");

		assertTrue(Files.isRegularFile(projection));
		Files.delete(projection);

		GenerationStore.CurrentSnapshot fallback = store.loadCurrent().orElseThrow();
		assertEquals(publication.record(), fallback.record());
		assertEquals(projection, fallback.projectionPath());
		assertFalse(fallback.hostingPaths().containsKey(""));

		GenerationStore.CurrentSnapshot repaired = store.loadCurrentAndRepair().orElseThrow();
		assertEquals(publication.record(), repaired.record());
		assertEquals(projection, repaired.hostingPaths().get(""));
		assertTrue(Files.isRegularFile(projection));
	}

	@Test
	void currentLoadFallsBackWhenProjectionBelongsToAnotherGeneration() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot firstCurrent = store.loadCurrent().orElseThrow();
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(firstCurrent), "");

		Files.writeString(tempDir.resolve("current-projection.json"), ConfigTools.GSON.toJson(first.record().toFields()), StandardCharsets.UTF_8);

		assertEquals(second.record(), store.loadCurrent().orElseThrow().record());
	}

	@Test
	void startupRejectsMissingCurrentObject() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication publication = store.publish(candidate("first"), Optional.empty(), "");
		String hash = publication.record().manifest().groups().get("main").files().values().iterator().next().sha1();
		Files.delete(DataRootResolver.objectFile(tempDir.resolve("objects"), hash));
		assertThrows(IOException.class, store::loadCurrent);
	}

	@Test
	void deepVerificationAllowsCollectedHistoricalObject() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();
		store.publish(candidate("second"), Optional.of(current), "");
		String historicalHash = first.record().manifest().groups().get("main").files().values().iterator().next().sha1();
		Files.delete(DataRootResolver.objectFile(tempDir.resolve("objects"), historicalHash));

		assertDoesNotThrow(() -> store.loadCurrent().orElseThrow());
		assertDoesNotThrow(() -> store.loadCurrentDeep().orElseThrow());
	}

	@Test
	void deepVerificationRejectsMissingOwnershipDelta() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication publication = store.publish(candidate("first"), Optional.empty(), "");
		Files.delete(tempDir.resolve("deltas").resolve(publication.record().metadata().generationId() + ".json"));

		assertDoesNotThrow(() -> store.loadCurrent().orElseThrow());
		assertThrows(IOException.class, store::loadCurrentDeep);
	}

	@Test
	void deepVerificationRejectsMissingCompactCatalogue() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication publication = store.publish(candidate("first"), Optional.empty(), "");
		Files.delete(tempDir.resolve("catalogues").resolve(publication.record().metadata().stateDigest() + ".json"));

		assertDoesNotThrow(() -> store.loadCurrent().orElseThrow());
		assertThrows(IOException.class, store::loadCurrentDeep);
	}

	@Test
	void deepHistoryRebuildsWhenProjectionIsMissing() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot firstCurrent = store.loadCurrent().orElseThrow();
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(firstCurrent), "");
		Files.delete(tempDir.resolve("current-projection.json"));

		assertEquals(second.record(), store.loadCurrent().orElseThrow().record());
		assertEquals(second.record(), store.loadCurrentDeep().orElseThrow().record());
		assertEquals(List.of(new GenerationHistoryEntry(first.record().manifest(), first.record().metadata()),
				new GenerationHistoryEntry(second.record().manifest(), second.record().metadata())), store.currentHistory());
	}

	@Test
	void repairRebuildsProjectionFromCompactMetadataWhenProjectionIsMissing() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot firstCurrent = store.loadCurrent().orElseThrow();
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(firstCurrent), "");
		Files.delete(tempDir.resolve("current-projection.json"));

		GenerationStore.CurrentSnapshot repaired = store.loadCurrentAndRepair().orElseThrow();
		assertEquals(second.record(), repaired.record());
		assertEquals(tempDir.resolve("current-projection.json"), repaired.hostingPaths().get(""));
		assertTrue(Files.isRegularFile(tempDir.resolve("current-projection.json")));
	}

	@Test
	void revertFindsTargetFromCompactHistoryWhenProjectionIsMissing() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot firstCurrent = store.loadCurrent().orElseThrow();
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(firstCurrent), "");
		Files.delete(tempDir.resolve("current-projection.json"));

		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();
		GenerationStore.Publication reverted = store.publishRevert(first.record().metadata().generationId(), Optional.of(current), "");

		assertEquals(second.record().metadata().generationId(), reverted.record().metadata().parentGenerationId());
		assertEquals(first.record().manifest(), reverted.record().manifest());
	}

	@Test
	void deepVerificationRejectsTamperedCurrentRecordIdentity() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication publication = store.publish(candidate("first"), Optional.empty(), "");
		String originalId = publication.record().metadata().generationId();
		String tamperedId = originalId.equals("0".repeat(40)) ? "1".repeat(40) : "0".repeat(40);
		Path projectionPath = publication.projectionPath();
		String projection = Files.readString(projectionPath, StandardCharsets.UTF_8).replace(originalId, tamperedId);
		Files.writeString(projectionPath, projection, StandardCharsets.UTF_8);

		GenerationStore.CurrentSnapshot current = assertDoesNotThrow(() -> store.loadCurrent().orElseThrow());
		assertFalse(current.hostingPaths().containsKey(""));
		assertThrows(IOException.class, store::loadCurrentDeep);
	}

	@Test
	void startupRejectsInvalidCurrentPointerMetadata() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		String pointer = "{\"schemaVersion\":" + (GenerationStore.CURRENT_POINTER_SCHEMA_VERSION + 1) + ",\"generationId\":\"" + "0".repeat(40) + "\"}";
		Files.writeString(tempDir.resolve("current.json"), pointer, StandardCharsets.UTF_8);

		assertThrows(IOException.class, store::loadCurrent);
	}

	@Test
	void revertCreatesTechnicalChildWithCumulativeLedger() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot firstCurrent = store.loadCurrent().orElseThrow();
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(firstCurrent), "");
		GenerationStore.CurrentSnapshot secondCurrent = store.loadCurrent().orElseThrow();

		GenerationStore.Publication reverted = store.publishRevert(first.record().metadata().generationId(), Optional.of(secondCurrent), "revert notes");

		assertEquals(GenerationStore.PublicationStatus.PUBLISHED, reverted.status());
		assertEquals(second.record().metadata().generationId(), reverted.record().metadata().parentGenerationId());
		assertEquals(first.record().metadata().generationId(), reverted.record().metadata().rollbackTargetGenerationId());
		assertEquals(first.record().manifest(), reverted.record().manifest());
		assertEquals(3, store.currentHistory().size());
		try (var catalogues = Files.list(tempDir.resolve("catalogues"))) {
			assertEquals(2, catalogues.count());
		}
		assertTrue(reverted.record().ownershipLedger().entries().get("config/example.txt").historicalHashes().size() >= 2);
	}

	@Test
	void compactionCheckpointReloadsCurrentAndTruncatesHistory() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "first notes");
		GenerationStore.CurrentSnapshot firstCurrent = store.loadCurrent().orElseThrow();
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(firstCurrent), "second notes");
		GenerationStore.CurrentSnapshot secondCurrent = store.loadCurrent().orElseThrow();
		GenerationStore.Publication third = store.publish(candidate("third"), Optional.of(secondCurrent), "third notes");

		GenerationStore.CompactionPreview preview = store.previewCompaction(third.record().metadata().generationId());
		assertEquals(List.of(first.record().metadata().generationId(), second.record().metadata().generationId()).stream().sorted().toList(), preview.rollbackUnavailableGenerationIds());
		assertTrue(preview.reclaimableBytes() > 0);
		GenerationStore.CompactionResult result = store.compactBefore(third.record().metadata().generationId());

		assertEquals(third.record().metadata().generationId(), result.boundaryGenerationId());
		assertEquals(List.of(first.record().metadata().generationId(), second.record().metadata().generationId()).stream().sorted().toList(), result.supersededGenerationIds());
		assertEquals(2, result.deletedCommitCount());
		assertEquals(2, result.deletedDeltaCount());
		assertEquals(2, result.deletedCatalogueCount());
		assertTrue(Files.exists(tempDir.resolve("checkpoint.json")));
		assertTrue(Files.exists(tempDir.resolve("commits").resolve(third.record().metadata().generationId() + ".json")));
		assertFalse(Files.exists(tempDir.resolve("commits").resolve(first.record().metadata().generationId() + ".json")));
		ModpackJsons.CompleteModpackContentFields compactedFields = ConfigTools.read(tempDir.resolve("current-projection.json"), ModpackJsons.CompleteModpackContentFields.class).orElseThrow();
		assertEquals(List.of("first notes", "second notes", "third notes"), GenerationPatchNoteHistory.fromFields(compactedFields).stream()
				.map(GenerationPatchNoteHistory.Entry::patchNotes).toList());
		GenerationHistoryIndex compactedIndex = GenerationHistoryIndex.fromFields(compactedFields.generationHistory);
		assertEquals(List.of(first.record().metadata().generationId(), second.record().metadata().generationId(), third.record().metadata().generationId()),
				compactedIndex.entries().stream().map(GenerationHistoryIndex.Entry::generationId).toList());
		assertFalse(compactedIndex.find(first.record().metadata().generationId()).orElseThrow().detailsAvailable());
		assertFalse(compactedIndex.find(first.record().metadata().generationId()).orElseThrow().rollbackAvailable());
		assertTrue(compactedIndex.find(third.record().metadata().generationId()).orElseThrow().detailsAvailable());
		assertEquals(third.record().metadata().generationId(), compactedIndex.compactionBoundaryGenerationId());
		GenerationStore.CompactionResult retry = store.compactBefore(third.record().metadata().generationId());
		assertEquals(result.supersededGenerationIds(), retry.supersededGenerationIds());
		assertEquals(0, retry.deletedCommitCount());
		assertEquals(0, retry.deletedDeltaCount());
		assertEquals(0, retry.deletedCatalogueCount());

		Files.delete(tempDir.resolve("current-projection.json"));
		GenerationStore reloaded = store(Instant.parse("2026-01-02T00:00:00Z"));
		assertEquals(third.record(), reloaded.loadCurrentDeep().orElseThrow().record());
		assertEquals(List.of(third.record().metadata().generationId()), reloaded.currentHistory().stream().map(entry -> entry.metadata().generationId()).toList());
		assertEquals("third notes", reloaded.loadCurrent().orElseThrow().record().metadata().patchNotes());
		reloaded.loadCurrentAndRepair().orElseThrow();
		ModpackJsons.CompleteModpackContentFields reloadedFields = ConfigTools.read(tempDir.resolve("current-projection.json"), ModpackJsons.CompleteModpackContentFields.class).orElseThrow();
		assertEquals(List.of("first notes", "second notes", "third notes"), GenerationPatchNoteHistory.fromFields(reloadedFields).stream()
				.map(GenerationPatchNoteHistory.Entry::patchNotes).toList());
		assertEquals(compactedIndex, GenerationHistoryIndex.fromFields(reloadedFields.generationHistory));
	}

	@Test
	void compactionPreservesTombstoneAndHistoricalHash() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		String historicalHash = first.record().manifest().groups().get("main").files().get("config/example.txt").sha1();
		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();
		GenerationStore.Publication removed = store.publish(emptyCandidate("removed"), Optional.of(current), "removed notes");

		store.compactBefore(removed.record().metadata().generationId());
		GenerationRecord reloaded = store.loadCurrentDeep().orElseThrow().record();
		OwnershipLedger.Entry entry = reloaded.ownershipLedger().entries().get("config/example.txt");
		assertEquals(OwnershipLedger.Status.TOMBSTONE, entry.currentStatus());
		assertTrue(entry.historicalHashes().stream().anyMatch(content -> content.sha1().equals(historicalHash)));
		assertEquals(removed.record(), reloaded);
	}

	@Test
	void publishAfterCompactionUsesCheckpointLedgerAndBoundary() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(current), "");
		store.compactBefore(second.record().metadata().generationId());

		GenerationStore reloaded = store(Instant.parse("2026-01-02T00:00:00Z"));
		GenerationStore.CurrentSnapshot compacted = reloaded.loadCurrent().orElseThrow();
		GenerationStore.Publication third = reloaded.publish(candidate("third"), Optional.of(compacted), "after compact");

		assertEquals(second.record().metadata().generationId(), third.record().metadata().parentGenerationId());
		assertEquals(List.of(second.record().metadata().generationId(), third.record().metadata().generationId()), reloaded.currentHistory().stream()
				.map(entry -> entry.metadata().generationId()).toList());
		assertEquals(third.record(), reloaded.loadCurrentDeep().orElseThrow().record());
		assertTrue(third.record().ownershipLedger().entries().get("config/example.txt").historicalHashes().size() >= 3);
		assertFalse(reloaded.currentHistory().stream().anyMatch(entry -> entry.metadata().generationId().equals(first.record().metadata().generationId())));
	}

	@Test
	void compactionCanRetainABoundaryOlderThanTheCurrentGeneration() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "first notes");
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(store.loadCurrent().orElseThrow()), "second notes");
		GenerationStore.Publication third = store.publish(candidate("third"), Optional.of(store.loadCurrent().orElseThrow()), "third notes");

		GenerationStore.CompactionResult result = store.compactBefore(second.record().metadata().generationId());

		assertEquals(List.of(first.record().metadata().generationId()), result.supersededGenerationIds());
		assertEquals(third.record(), store.loadCurrentDeep().orElseThrow().record());
		assertEquals(List.of(second.record().metadata().generationId(), third.record().metadata().generationId()), store.currentHistory().stream()
				.map(entry -> entry.metadata().generationId()).toList());
		GenerationHistoryIndex index = store.currentHistoryIndex().orElseThrow();
		assertEquals(second.record().metadata().generationId(), index.compactionBoundaryGenerationId());
		assertEquals(List.of(first.record().metadata().generationId(), second.record().metadata().generationId(), third.record().metadata().generationId()),
				index.entries().stream().map(GenerationHistoryIndex.Entry::generationId).toList());
		assertFalse(index.find(first.record().metadata().generationId()).orElseThrow().rollbackAvailable());
		assertTrue(index.find(second.record().metadata().generationId()).orElseThrow().rollbackAvailable());
		assertTrue(index.find(third.record().metadata().generationId()).orElseThrow().rollbackAvailable());
	}

	@Test
	void revertCanTargetTheCompactionBoundary() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(current), "");
		store.compactBefore(second.record().metadata().generationId());

		GenerationStore.CurrentSnapshot compacted = store.loadCurrent().orElseThrow();
		GenerationStore.Publication reverted = store.publishRevert(second.record().metadata().generationId(), Optional.of(compacted), "revert after compact");

		assertEquals(second.record().metadata().generationId(), reverted.record().metadata().rollbackTargetGenerationId());
		assertEquals(second.record().manifest(), reverted.record().manifest());
		assertEquals(List.of(second.record().metadata().generationId(), reverted.record().metadata().generationId()), store.currentHistory().stream()
				.map(entry -> entry.metadata().generationId()).toList());
	}

	@Test
	void malformedCheckpointRefusesCompactionWithoutMutation() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(current), "");
		Path oldCommit = tempDir.resolve("commits").resolve(first.record().metadata().generationId() + ".json");
		Path oldDelta = tempDir.resolve("deltas").resolve(first.record().metadata().generationId() + ".json");
		Path checkpoint = tempDir.resolve("checkpoint.json");
		byte[] commitBefore = Files.readAllBytes(oldCommit);
		byte[] deltaBefore = Files.readAllBytes(oldDelta);
		Files.writeString(checkpoint, "not-json", StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> store.compactBefore(second.record().metadata().generationId()));
		assertArrayEquals(commitBefore, Files.readAllBytes(oldCommit));
		assertArrayEquals(deltaBefore, Files.readAllBytes(oldDelta));
		assertEquals("not-json", Files.readString(checkpoint, StandardCharsets.UTF_8));
		assertThrows(IOException.class, store::loadCurrent);
	}

	@Test
	void compactionRetryUsesCheckpointReceiptAfterDeletionInterruption() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot firstCurrent = store.loadCurrent().orElseThrow();
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(firstCurrent), "");
		GenerationStore.CurrentSnapshot secondCurrent = store.loadCurrent().orElseThrow();
		GenerationStore.Publication third = store.publish(candidate("third"), Optional.of(secondCurrent), "");
		AtomicBoolean interrupt = new AtomicBoolean(true);
		GenerationStore interrupted = new GenerationStore(tempDir, Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC), () -> {}, path -> {
			if (interrupt.getAndSet(false)) throw new IOException("injected compaction interruption");
		});

		assertThrows(IOException.class, () -> interrupted.compactBefore(third.record().metadata().generationId()));
		assertTrue(Files.exists(tempDir.resolve("checkpoint.json")));
		assertTrue(Files.exists(tempDir.resolve("commits").resolve(first.record().metadata().generationId() + ".json")));
		assertTrue(Files.exists(tempDir.resolve("deltas").resolve(first.record().metadata().generationId() + ".json")));
		assertTrue(Files.exists(tempDir.resolve("catalogues").resolve(first.record().metadata().stateDigest() + ".json")));

		GenerationStore.CompactionResult retry = interrupted.compactBefore(third.record().metadata().generationId());
		assertEquals(List.of(first.record().metadata().generationId(), second.record().metadata().generationId()).stream().sorted().toList(), retry.supersededGenerationIds());
		assertTrue(retry.deletedCommitCount() >= 2);
		assertTrue(retry.deletedDeltaCount() >= 2);
		assertTrue(retry.deletedCatalogueCount() >= 2);
		assertFalse(Files.exists(tempDir.resolve("commits").resolve(first.record().metadata().generationId() + ".json")));
		assertFalse(Files.exists(tempDir.resolve("deltas").resolve(first.record().metadata().generationId() + ".json")));
		assertFalse(Files.exists(tempDir.resolve("catalogues").resolve(first.record().metadata().stateDigest() + ".json")));
		assertEquals(third.record(), interrupted.loadCurrentDeep().orElseThrow().record());

		GenerationStore.CurrentSnapshot current = interrupted.loadCurrent().orElseThrow();
		GenerationStore.Publication published = interrupted.publish(candidate("after-retry"), Optional.of(current), "");
		assertEquals(published.record(), interrupted.loadCurrentDeep().orElseThrow().record());
	}

	@Test
	void normalReadFinishesInterruptedCompactionAndReconcilesProjection() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "first notes");
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(store.loadCurrent().orElseThrow()), "second notes");
		byte[] staleProjection = Files.readAllBytes(tempDir.resolve("current-projection.json"));
		GenerationStore.Publication third = store.publish(candidate("third"), Optional.of(store.loadCurrent().orElseThrow()), "third notes");
		AtomicBoolean interrupt = new AtomicBoolean(true);
		GenerationStore interrupted = new GenerationStore(tempDir, Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC), () -> {}, path -> {
			if (interrupt.getAndSet(false)) throw new IOException("injected compaction interruption");
		});

		assertThrows(IOException.class, () -> interrupted.compactBefore(third.record().metadata().generationId()));
		Files.write(tempDir.resolve("current-projection.json"), staleProjection);
		GenerationStore recovered = store(Instant.parse("2026-01-03T00:00:00Z"));
		GenerationHistoryIndex index = recovered.currentHistoryIndex().orElseThrow();

		assertFalse(index.find(first.record().metadata().generationId()).orElseThrow().rollbackAvailable());
		assertFalse(index.find(second.record().metadata().generationId()).orElseThrow().rollbackAvailable());
		assertFalse(Files.exists(tempDir.resolve("commits").resolve(first.record().metadata().generationId() + ".json")));
		ModpackJsons.CompleteModpackContentFields projection = ConfigTools.read(tempDir.resolve("current-projection.json"), ModpackJsons.CompleteModpackContentFields.class).orElseThrow();
		assertEquals(index, GenerationHistoryIndex.fromFields(projection.generationHistory));
	}

	private GenerationStore store(Instant instant) {
		return new GenerationStore(tempDir, Clock.fixed(instant, ZoneOffset.UTC), () -> {});
	}

	private ModpackCandidate candidate(String description) throws Exception {
		byte[] bytes = ("content-" + description).getBytes(StandardCharsets.UTF_8);
		Path staging = tempDir.resolve("staging");
		Files.createDirectories(staging);
		Path staged = Files.createTempFile(staging, "candidate-", ".staged");
		Files.write(staged, bytes);
		String hash = HashUtils.getHash(staged);
		GroupManifest manifest = manifest(description, hash, bytes.length);
		return new ModpackCandidate(manifest, new TreeMap<>(Map.of(hash, new StagedObject(hash, bytes.length, staged))), new TreeMap<>(), List.of(), List.of());
	}

	private static ModpackCandidate emptyCandidate(String description) {
		return new ModpackCandidate(emptyManifest(description), new TreeMap<>(), new TreeMap<>(), List.of(), List.of());
	}

	private static GroupManifest manifest(String description, String hash, long size) {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		var group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.description = description;
		group.files = Map.of("config/example.txt", new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(size), "config", false, hash, null));
		fields.groups = Map.of("main", group);
		return GroupManifestValidator.validate(fields);
	}

	private static GroupManifest emptyManifest(String description) {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		var group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.description = description;
		group.files = Map.of();
		fields.groups = Map.of("main", group);
		return GroupManifestValidator.validate(fields);
	}
}
