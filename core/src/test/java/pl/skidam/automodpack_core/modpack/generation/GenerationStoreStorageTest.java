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

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.StagedObject;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.utils.HashUtils;

class GenerationStoreStorageTest {
	@TempDir
	Path tempDir;

	@Test
	void measuresRecordsObjectsStagingAndReferences() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		GenerationStore.Publication first = store.publish(candidate("first"), Optional.empty(), "");
		GenerationStore.CurrentSnapshot firstCurrent = store.loadCurrent().orElseThrow();
		GenerationStore.Publication second = store.publish(candidate("second"), Optional.of(firstCurrent), "");
		Path stagingFile = tempDir.resolve("staging/leftover.tmp");
		Files.writeString(stagingFile, "unfinished", StandardCharsets.UTF_8);

		GenerationStore.StorageReport report = store.measureStorage();
		long recordBytes = Files.size(first.recordPath()) + Files.size(second.recordPath());
		long objectBytes = Files.size(store.objectRoot().resolve(first.record().manifest().groups().get("main").files().get("config/example.txt").sha1()))
				+ Files.size(store.objectRoot().resolve(second.record().manifest().groups().get("main").files().get("config/example.txt").sha1()));

		assertEquals(2, report.recordCount());
		assertEquals(recordBytes, report.recordBytes());
		assertEquals(2, report.immutableObjectCount());
		assertEquals(objectBytes, report.immutableObjectBytes());
		assertEquals(1, report.stagingFileCount());
		assertEquals(Files.size(stagingFile), report.stagingBytes());
		assertEquals(2, report.referencedObjectCount());
		assertEquals(objectBytes, report.referencedObjectBytes());
		assertEquals(5, report.objectReferenceCount());
		assertEquals(2d / 5d, report.uniqueObjectReferenceRatio().orElseThrow());
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

		GenerationStore.CollectionResult result = store.collect(Set.of(), Set.of());

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
		store.publish(candidate("current"), Optional.empty(), "");
		String detachedHash = createObject("detached-generation");
		GenerationRecord detached = GenerationRecord.create(manifest("detached", detachedHash, Files.size(store.objectRoot().resolve(detachedHash)), "abc1234"), null,
				Instant.parse("2025-12-31T00:00:00Z"), "");
		Path detachedPath = tempDir.resolve("records").resolve(detached.metadata().generationId() + ".json");
		Files.writeString(detachedPath, ConfigTools.GSON.toJson(detached.toFields()), StandardCharsets.UTF_8);
		String pinnedHash = createObject("explicit-object-pin");

		GenerationStore.CollectionResult pinned = store.collect(Set.of(detached.metadata().generationId()), Set.of(pinnedHash));
		assertEquals(3, pinned.beforeObjectCount());
		assertEquals(3, pinned.afterObjectCount());
		assertEquals(0, pinned.deletedObjectCount());
		assertTrue(Files.exists(store.objectRoot().resolve(detachedHash)));
		assertTrue(Files.exists(store.objectRoot().resolve(pinnedHash)));

		GenerationStore.CollectionResult unpinnedGeneration = store.collect(Set.of(), Set.of(pinnedHash));
		assertEquals(1, unpinnedGeneration.deletedObjectCount());
		assertFalse(Files.exists(store.objectRoot().resolve(detachedHash)));
		assertTrue(Files.exists(store.objectRoot().resolve(pinnedHash)));
		assertTrue(Files.exists(detachedPath));
	}

	@Test
	void collectorRequiresCurrentGeneration() throws Exception {
		GenerationStore store = store(Instant.parse("2026-01-01T00:00:00Z"));
		store.publish(candidate("current"), Optional.empty(), "");
		Files.delete(tempDir.resolve("current.json"));

		assertThrows(IOException.class, () -> store.collect(Set.of(), Set.of()));
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
		Jsons.CompleteModpackContentFields fields = new Jsons.CompleteModpackContentFields();
		fields.modpackId = modpackId;
		fields.selectionTags = Map.of();
		var group = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		group.description = description;
		group.files = Map.of("config/example.txt", new Jsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(size), "config", false, false, false, hash, null));
		fields.groups = Map.of("main", group);
		return GroupManifestValidator.validate(fields);
	}
}
