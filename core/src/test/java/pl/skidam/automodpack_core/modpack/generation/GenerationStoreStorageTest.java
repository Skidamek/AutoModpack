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
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.StagedObject;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.utils.HashUtils;

class GenerationStoreStorageTest {
	@TempDir
	Path tempDir;

	@Test
	void measuresCompactObjectsStagingAndReferences() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot firstCurrent = store.loadCurrent().orElseThrow();
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(firstCurrent), "");
		Path stagingFile = tempDir.resolve("staging/leftover.tmp");
		Files.writeString(stagingFile, "unfinished", StandardCharsets.UTF_8);

		GenerationStore.StorageReport report = store.measureStorage();
		long catalogueBytes = directoryBytes(tempDir.resolve("catalogues"));
		long commitBytes = directoryBytes(tempDir.resolve("commits"));
		long deltaBytes = directoryBytes(tempDir.resolve("deltas"));
		long objectBytes = Files.size(store.objectRoot().resolve(first.record().manifest().groups().get("main").files().get("config/example.txt").sha1()))
				+ Files.size(store.objectRoot().resolve(second.record().manifest().groups().get("main").files().get("config/example.txt").sha1()));

		assertEquals(2, report.catalogueCount());
		assertEquals(catalogueBytes, report.catalogueBytes());
		assertEquals(2, report.commitCount());
		assertEquals(commitBytes, report.commitBytes());
		assertEquals(2, report.deltaCount());
		assertEquals(deltaBytes, report.deltaBytes());
		assertEquals(2, report.immutableObjectCount());
		assertEquals(objectBytes, report.immutableObjectBytes());
		assertEquals(1, report.stagingFileCount());
		assertEquals(Files.size(stagingFile), report.stagingBytes());
		assertEquals(2, report.referencedObjectCount());
		assertEquals(objectBytes, report.referencedObjectBytes());
		assertEquals(3, report.objectReferenceCount());
		assertEquals(2d / 3d, report.uniqueObjectReferenceRatio().orElseThrow());
	}

	@Test
	void collectorRetainsCurrentObjects() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication publication = store.publish(candidate("current"), Optional.empty(), "");
		String hash = publication.record().manifest().groups().get("main").files().get("config/example.txt").sha1();

		GenerationStore.CollectionResult result = store.collectUnreachableObjects(Set.of(), Set.of());

		assertEquals(1, result.beforeObjectCount());
		assertEquals(1, result.afterObjectCount());
		assertEquals(0, result.deletedObjectCount());
		assertEquals(0, result.deletedObjectBytes());
		assertTrue(Files.exists(store.objectRoot().resolve(hash)));
	}

	@Test
	void collectorDeletesUnreferencedObject() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication publication = store.publish(candidate("current"), Optional.empty(), "");
		String orphanHash = createObject("orphan");
		long orphanBytes = Files.size(store.objectRoot().resolve(orphanHash));

		GenerationStore.CollectionResult result = store.collectUnreachableObjects(Set.of(), Set.of());

		assertEquals(2, result.beforeObjectCount());
		assertEquals(1, result.afterObjectCount());
		assertEquals(1, result.deletedObjectCount());
		assertEquals(orphanBytes, result.deletedObjectBytes());
		assertEquals(result.beforeObjectBytes() - orphanBytes, result.afterObjectBytes());
		assertTrue(Files.exists(store.objectRoot().resolve(publication.record().manifest().groups().get("main").files().get("config/example.txt").sha1())));
		assertFalse(Files.exists(store.objectRoot().resolve(orphanHash)));
	}

	@Test
	void collectorHonorsExplicitObjectAndGenerationPins() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();
		store.publish(candidate("current"), Optional.of(current), "");
		String pinnedHash = createObject("explicit-object-pin");

		GenerationStore.CollectionResult pinned = store.collectUnreachableObjects(Set.of(first.record().metadata().generationId()), Set.of(pinnedHash));
		assertEquals(3, pinned.beforeObjectCount());
		assertEquals(3, pinned.afterObjectCount());
		assertEquals(0, pinned.deletedObjectCount());
		assertTrue(Files.exists(store.objectRoot().resolve(pinnedHash)));

		GenerationStore.CollectionResult unpinnedGeneration = store.collectUnreachableObjects(Set.of(), Set.of(pinnedHash));
		assertEquals(0, unpinnedGeneration.deletedObjectCount());
		assertTrue(Files.exists(store.objectRoot().resolve(pinnedHash)));
		assertTrue(Files.exists(store.objectRoot().resolve(first.record().manifest().groups().get("main").files().get("config/example.txt").sha1())));
	}

	@Test
	void collectorUsesCheckpointLedgerAfterCompaction() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();
		store.publish(candidate("second"), Optional.of(current), "");
		store.compact();
		String historicalHash = first.record().manifest().groups().get("main").files().get("config/example.txt").sha1();
		String orphanHash = createObject("orphan-after-compaction");

		GenerationStore.CollectionResult result = store.collectUnreachableObjects(Set.of(), Set.of());

		assertEquals(1, result.deletedObjectCount());
		assertFalse(Files.exists(store.objectRoot().resolve(orphanHash)));
		assertTrue(Files.exists(store.objectRoot().resolve(historicalHash)));
	}

	@Test
	void collectorRequiresCurrentGeneration() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		store.publish(candidate("current"), Optional.empty(), "");
		Files.delete(tempDir.resolve("current.json"));

		assertThrows(IOException.class, () -> store.collectUnreachableObjects(Set.of(), Set.of()));
	}

	private GenerationStore store(Instant instant) {
		return new GenerationStore(tempDir, Clock.fixed(instant, ZoneOffset.UTC), () -> {});
	}

	private String createObject(String value) throws Exception {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		Path source = Files.createTempFile(tempDir, "object-", ".source");
		Files.write(source, bytes);
		String hash = HashUtils.getHash(source);
		Files.copy(source, tempDir.resolve("objects").resolve(hash));
		Files.delete(source);
		return hash;
	}

	private long directoryBytes(Path directory) throws IOException {
		try (var files = Files.list(directory)) {
			return files.mapToLong(this::fileSize).sum();
		}
	}

	private long fileSize(Path path) {
		try {
			return Files.size(path);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private ModpackCandidate candidate(String description) throws Exception {
		byte[] bytes = ("content-" + description).getBytes(StandardCharsets.UTF_8);
		Path staging = tempDir.resolve("staging");
		Files.createDirectories(staging);
		Path staged = Files.createTempFile(staging, "candidate-", ".staged");
		Files.write(staged, bytes);
		String hash = HashUtils.getHash(staged);
		GroupManifest manifest = manifest(description, hash, bytes.length, "abc1234");
		return new ModpackCandidate(manifest, new TreeMap<>(Map.of(hash, new StagedObject(hash, bytes.length, staged))), new TreeMap<>(), List.of(), List.of());
	}

	private static GroupManifest manifest(String description, String hash, long size, String modpackId) {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = modpackId;
		var group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.description = description;
		group.files = Map.of("config/example.txt", new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(size), "config", false, false, hash, null));
		fields.groups = Map.of("main", group);
		return GroupManifestValidator.validate(fields);
	}
}
