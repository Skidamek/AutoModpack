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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.StagedObject;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.utils.HashUtils;

class GenerationStoreTest {
	@TempDir
	Path tempDir;

	@Test
	void publishesRootThenParentAndReturnsNoChanges() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "ignored");
		assertEquals(GenerationStore.PublicationStatus.PUBLISHED, first.status());
		assertEquals("", first.record().metadata().parentGenerationId());
		assertTrue(Files.exists(tempDir.resolve("catalogues").resolve(first.record().metadata().stateDigest() + ".json")));
		assertTrue(Files.exists(tempDir.resolve("commits").resolve(first.record().metadata().generationId() + ".json")));
		assertFalse(Files.exists(tempDir.resolve("records")));
		String pointer = Files.readString(tempDir.resolve("current.json"), StandardCharsets.UTF_8);

		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();
		GenerationStore.Publication unchanged = store.publish(candidate("first"), Optional.of(current), "different notes");
		assertEquals(GenerationStore.PublicationStatus.NO_CHANGES, unchanged.status());
		assertEquals(pointer, Files.readString(tempDir.resolve("current.json"), StandardCharsets.UTF_8));
		assertFalse(Files.exists(tempDir.resolve("records")));

		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(current), "");
		assertEquals(GenerationStore.PublicationStatus.PUBLISHED, second.status());
		assertEquals(first.record().metadata().generationId(), second.record().metadata().parentGenerationId());
		assertEquals(second.record(), store.loadCurrent().orElseThrow().record());
	}

	@Test
	void concurrentPublishersFromOneParentCannotBothSucceed() throws Exception {
		GenerationStore firstStore = store(Instant.parse("2026-01-01T00:00:00Z"));
		firstStore.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot parent = firstStore.loadCurrent().orElseThrow();
		GenerationStore secondStore = store(Instant.parse("2026-01-02T00:00:00Z"));
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
		Files.delete(tempDir.resolve("objects").resolve(hash));
		assertThrows(IOException.class, store::loadCurrent);
	}

	@Test
	void deepVerificationRejectsMissingHistoricalObject() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();
		store.publish(candidate("second"), Optional.of(current), "");
		String historicalHash = first.record().manifest().groups().get("main").files().values().iterator().next().sha1();
		Files.delete(tempDir.resolve("objects").resolve(historicalHash));

		assertDoesNotThrow(() -> store.loadCurrent().orElseThrow());
		assertThrows(IOException.class, store::loadCurrentDeep);
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

	private static GroupManifest manifest(String description, String hash, long size) {
		Jsons.CompleteModpackContentFields fields = new Jsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.selectionTags = Map.of();
		var group = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		group.description = description;
		group.files = Map.of("config/example.txt", new Jsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(size), "config", false, false, false, hash, null));
		fields.groups = Map.of("main", group);
		return GroupManifestValidator.validate(fields);
	}
}
