package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
		String pointer = Files.readString(tempDir.resolve("current.json"), StandardCharsets.UTF_8);
		long records = countRecords();

		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();
		GenerationStore.Publication unchanged = store.publish(candidate("first"), Optional.of(current), "different notes");
		assertEquals(GenerationStore.PublicationStatus.NO_CHANGES, unchanged.status());
		assertEquals(pointer, Files.readString(tempDir.resolve("current.json"), StandardCharsets.UTF_8));
		assertEquals(records, countRecords());

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
			for (var result : java.util.List.of(first, second)) {
				try {
					assertEquals(GenerationStore.PublicationStatus.PUBLISHED, result.get().status());
					successes++;
				} catch (ExecutionException e) {
					assertInstanceOf(java.io.IOException.class, e.getCause());
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
				() -> { throw new java.io.IOException("injected pointer failure"); });
		GenerationStore.CurrentSnapshot current = failing.loadCurrent().orElseThrow();
		assertThrows(java.io.IOException.class, () -> failing.publish(candidate("second"), Optional.of(current), ""));
		assertArrayEquals(oldPointer, Files.readAllBytes(tempDir.resolve("current.json")));
		assertEquals(first.record(), failing.loadCurrent().orElseThrow().record());
		assertTrue(countRecords() > 1);
	}

	@Test
	void missingCurrentNeverGuessesAnOrphanRecord() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication publication = store.publish(candidate("first"), Optional.empty(), "");
		Files.delete(tempDir.resolve("current.json"));
		assertTrue(store.loadCurrent().isEmpty());
		assertTrue(Files.exists(publication.recordPath()));
	}

	@Test
	void startupRejectsMissingCurrentObject() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication publication = store.publish(candidate("first"), Optional.empty(), "");
		String hash = publication.record().manifest().groups().get("main").files().values().iterator().next().sha1();
		Files.delete(tempDir.resolve("objects").resolve(hash));
		assertThrows(java.io.IOException.class, store::loadCurrent);
	}

	@Test
	void startupRejectsMissingHistoricalObject() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();
		store.publish(candidate("second"), Optional.of(current), "");
		String historicalHash = first.record().manifest().groups().get("main").files().values().iterator().next().sha1();
		Files.delete(tempDir.resolve("objects").resolve(historicalHash));

		assertThrows(java.io.IOException.class, store::loadCurrent);
	}

	@Test
	void startupRejectsTamperedCurrentRecordIdentity() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication publication = store.publish(candidate("first"), Optional.empty(), "");
		String originalId = publication.record().metadata().generationId();
		String tamperedId = originalId.equals("0".repeat(40)) ? "1".repeat(40) : "0".repeat(40);
		Path recordPath = publication.recordPath();
		String record = Files.readString(recordPath, StandardCharsets.UTF_8).replace(originalId, tamperedId);
		Files.writeString(recordPath, record, StandardCharsets.UTF_8);

		assertThrows(java.io.IOException.class, store::loadCurrent);
	}

	@Test
	void startupRejectsInvalidCurrentPointerMetadata() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		String pointer = "{\"schemaVersion\":" + (GenerationStore.CURRENT_POINTER_SCHEMA_VERSION + 1) + ",\"generationId\":\"" + "0".repeat(40) + "\"}";
		Files.writeString(tempDir.resolve("current.json"), pointer, StandardCharsets.UTF_8);

		assertThrows(java.io.IOException.class, store::loadCurrent);
	}

	private long countRecords() throws Exception {
		try (var records = Files.list(tempDir.resolve("records"))) {
			return records.count();
		}
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
		return new ModpackCandidate(manifest, new TreeMap<>(Map.of(hash, new StagedObject(hash, bytes.length, staged))), new TreeMap<>(), java.util.List.of(), java.util.List.of());
	}

	private static GroupManifest manifest(String description, String hash, long size) {
		Jsons.CompleteModpackContentFields fields = new Jsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.selectionTags = Map.of();
		var group = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		group.description = description;
		group.files = Map.of("config/example.txt", new Jsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(size), "other", false, false, false, hash, null));
		fields.groups = Map.of("main", group);
		return GroupManifestValidator.validate(fields);
	}
}
