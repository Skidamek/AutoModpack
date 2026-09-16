package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.StorageJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryEntry;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryIndex;
import pl.skidam.automodpack_core.modpack.generation.GenerationIdentity;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.ClientObjectStore;

class ClientGenerationStoreTest {
	private static final String FIRST_PACK = "abc1234";
	private static final String SECOND_PACK = "def5678";
	private static final String SELECTION_DIGEST = "1".repeat(40);

	@TempDir
	Path temporaryDirectory;

	@Test
	void retainsNewestRecordForEveryPackAndAnOlderActiveRecord() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "shared-object");
		long size = Files.size(storage.objectsDirectory().resolve(hash));
		GenerationRecord first = record(FIRST_PACK, hash, size, Instant.parse("2026-01-01T00:00:00Z"), null);
		GenerationRecord middle = record(FIRST_PACK, hash, size, Instant.parse("2026-01-02T00:00:00Z"), first);
		GenerationRecord newest = record(FIRST_PACK, hash, size, Instant.parse("2026-01-03T00:00:00Z"), middle);
		GenerationRecord other = record(SECOND_PACK, hash, size, Instant.parse("2026-01-01T00:00:00Z"), null);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		for (GenerationRecord record : List.of(first, middle, newest, other)) generations.write(record);
		storage.writeActiveState(FIRST_PACK, first.metadata().generationId());

		ClientGenerationStore.CompactionResult result = generations.compact();

		assertEquals(List.of(first.metadata().generationId(), newest.metadata().generationId(), other.metadata().generationId()).stream().sorted().toList(), result.retainedGenerationIds());
		assertEquals(List.of(middle.metadata().generationId()), result.removedGenerationIds());
		assertTrue(Files.exists(storage.generationManifest(first.metadata().generationId())));
		assertTrue(Files.exists(storage.generationManifest(newest.metadata().generationId())));
		assertFalse(Files.exists(storage.generationDirectory(middle.metadata().generationId())));
		assertEquals(4, result.generationRecordCountBefore());
		assertEquals(3, result.generationRecordCountAfter());
	}

	@Test
	void compactionCollectsReplacedObjectButPreservesHistoricalLedgerMetadata() throws Exception {
		ClientStorage storage = storage();
		String oldContent = "old-object";
		String currentContent = "current-object";
		String oldHash = store(storage, oldContent);
		String currentHash = store(storage, currentContent);
		GenerationRecord old = record(FIRST_PACK, oldHash, oldContent.getBytes(StandardCharsets.UTF_8).length, Instant.parse("2026-01-01T00:00:00Z"), null);
		GenerationRecord current = record(FIRST_PACK, currentHash, currentContent.getBytes(StandardCharsets.UTF_8).length, Instant.parse("2026-01-02T00:00:00Z"), old);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(old);
		generations.write(current);
		storage.writeActiveState(FIRST_PACK, current.metadata().generationId());

		ClientGenerationStore.CompactionResult result = generations.compact();

		assertEquals(1, result.objectCollection().deletedObjectCount());
		assertFalse(Files.exists(storage.objectsDirectory().resolve(oldHash)));
		assertTrue(Files.exists(storage.objectsDirectory().resolve(currentHash)));
		assertDoesNotThrow(() -> ClientObjectStore.validate(storage));
		GenerationRecord retained = generations.read(current.metadata().generationId()).orElseThrow();
		assertTrue(retained.ownershipLedger().entries().get("mods/test.jar").historicalHashes().stream()
				.anyMatch(content -> content.sha1().equals(oldHash) && content.size() == oldContent.getBytes(StandardCharsets.UTF_8).length));
	}

	@Test
	void deactivatedPackCompactsWhenUnselectedCatalogueObjectWasNeverCached() throws Exception {
		ClientStorage storage = storage();
		String selectedHash = store(storage, "selected-object");
		String orphanHash = store(storage, "orphan-object");
		String uncachedOptionalHash = "f".repeat(40);
		GroupManifest.GroupFile selectedFile = new GroupManifest.GroupFile(Files.size(storage.objectsDirectory().resolve(selectedHash)), "mod", false, false,
				selectedHash, null);
		GroupManifest.GroupFile optionalFile = new GroupManifest.GroupFile(176, "config", false, false, uncachedOptionalHash, null);
		GroupManifest.Group selectedGroup = new GroupManifest.Group("Core", "", "", "", true, true, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("mods/test.jar", selectedFile)));
		GroupManifest.Group optionalGroup = new GroupManifest.Group("Optional", "", "", "", false, false, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("config/optional.json", optionalFile)));
		GenerationRecord record = GenerationRecord.create(
				new GroupManifest(FIRST_PACK, "Test", "", "", "", "", new TreeMap<>(Map.of("main", selectedGroup, "optional", optionalGroup))), null,
				Instant.parse("2026-01-01T00:00:00Z"), "notes");
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(record);
		new ClientSelectionStore(storage.selectionFile()).compareAndSet(FIRST_PACK, null, new SelectionIntent(Set.of()));
		storage.writeActiveState(FIRST_PACK, record.metadata().generationId());
		storage.clearActiveState();

		ClientGenerationStore.CompactionResult result = generations.compact();

		assertEquals(1, result.objectCollection().deletedObjectCount());
		assertTrue(Files.exists(storage.objectsDirectory().resolve(selectedHash)));
		assertFalse(Files.exists(storage.objectsDirectory().resolve(orphanHash)));
		assertFalse(Files.exists(storage.objectsDirectory().resolve(uncachedOptionalHash)));
	}

	@Test
	void removesGeneratedCopiesOnlyForRemovedGenerations() throws Exception {
		ClientStorage storage = storage();
		String recordHash = store(storage, "record-object");
		String oldGeneratedHash = store(storage, "old-generated-object");
		String newGeneratedHash = store(storage, "new-generated-object");
		long recordSize = Files.size(storage.objectsDirectory().resolve(recordHash));
		GenerationRecord old = record(FIRST_PACK, recordHash, recordSize, Instant.parse("2026-01-01T00:00:00Z"), null);
		GenerationRecord newest = record(FIRST_PACK, recordHash, recordSize, Instant.parse("2026-01-02T00:00:00Z"), old);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(old);
		generations.write(newest);
		storage.writeActiveState(FIRST_PACK, newest.metadata().generationId());
		new GeneratedCopyState(FIRST_PACK, old.metadata().generationId(), SELECTION_DIGEST,
				List.of(new GeneratedCopyState.Entry("mods/old-generated.jar", oldGeneratedHash, Files.size(storage.objectsDirectory().resolve(oldGeneratedHash))))).write(storage);
		new GeneratedCopyState(FIRST_PACK, newest.metadata().generationId(), SELECTION_DIGEST,
				List.of(new GeneratedCopyState.Entry("mods/new-generated.jar", newGeneratedHash, Files.size(storage.objectsDirectory().resolve(newGeneratedHash))))).write(storage);

		ClientGenerationStore.CompactionResult result = generations.compact();

		assertEquals(2, result.generatedCopyCountBefore());
		assertEquals(1, result.generatedCopyCountAfter());
		assertFalse(Files.exists(storage.generatedCopiesGenerationDirectory(FIRST_PACK, old.metadata().generationId())));
		assertTrue(Files.exists(storage.generatedCopiesFile(FIRST_PACK, newest.metadata().generationId(), SELECTION_DIGEST)));
		assertTrue(Files.exists(storage.objectsDirectory().resolve(newGeneratedHash)));
	}

	@Test
	void malformedRecordRefusesWithoutMutation() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "valid-object");
		GenerationRecord valid = record(FIRST_PACK, hash, Files.size(storage.objectsDirectory().resolve(hash)), Instant.parse("2026-01-01T00:00:00Z"), null);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(valid);
		storage.writeActiveState(FIRST_PACK, valid.metadata().generationId());
		Path local = Files.writeString(storage.gamePath("mods/local.jar"), "local", StandardCharsets.UTF_8);
		String malformedId = "0".repeat(40);
		Files.createDirectories(storage.generationDirectory(malformedId));
		Files.writeString(storage.generationManifest(malformedId), "{}", StandardCharsets.UTF_8);

		assertThrows(IOException.class, generations::compact);

		assertTrue(Files.exists(storage.generationManifest(valid.metadata().generationId())));
		assertTrue(Files.exists(storage.generationManifest(malformedId)));
		assertTrue(Files.exists(local));
		assertEquals(valid.metadata().generationId(), storage.readActiveState().generationId);
	}

	@Test
	void sameGenerationRepairDoesNotReplaceCompletePatchNoteHistoryWithPartialMetadata() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "generation-object");
		GenerationRecord parent = record(FIRST_PACK, hash, Files.size(storage.objectsDirectory().resolve(hash)), Instant.parse("2026-01-01T00:00:00Z"), null);
		GenerationRecord current = record(FIRST_PACK, hash, Files.size(storage.objectsDirectory().resolve(hash)), Instant.parse("2026-01-02T00:00:00Z"), parent);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		List<GenerationPatchNoteHistory.Entry> completeHistory = GenerationPatchNoteHistory.fromRecords(List.of(parent, current));

		generations.write(current, completeHistory);
		assertDoesNotThrow(() -> generations.write(current, GenerationPatchNoteHistory.forRecord(current)));

		assertEquals(completeHistory, generations.patchNotesHistory(current.metadata().generationId()));
	}

	@Test
	void sameGenerationRepairRejectsConflictingCompletePatchNoteHistory() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "generation-object");
		GenerationRecord parent = record(FIRST_PACK, hash, Files.size(storage.objectsDirectory().resolve(hash)), Instant.parse("2026-01-01T00:00:00Z"), null);
		GenerationRecord current = record(FIRST_PACK, hash, Files.size(storage.objectsDirectory().resolve(hash)), Instant.parse("2026-01-02T00:00:00Z"), parent);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		List<GenerationPatchNoteHistory.Entry> completeHistory = GenerationPatchNoteHistory.fromRecords(List.of(parent, current));
		GenerationPatchNoteHistory.Entry conflictingParent = new GenerationPatchNoteHistory.Entry(parent.metadata().schemaVersion(), parent.metadata().generationId(), parent.metadata().parentGenerationId(),
				parent.metadata().createdAt(), "conflicting notes", GenerationIdentity.patchNotesDigest("conflicting notes"));

		generations.write(current, completeHistory);

		assertThrows(IOException.class, () -> generations.write(current, List.of(conflictingParent, completeHistory.get(1))));
		assertEquals(completeHistory, generations.patchNotesHistory(current.metadata().generationId()));
	}

	@Test
	void sameGenerationRepairMergesHistoryIndexWithoutDiscardingExistingDetails() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "generation-object");
		GenerationRecord parent = record(FIRST_PACK, hash, Files.size(storage.objectsDirectory().resolve(hash)), Instant.parse("2026-01-01T00:00:00Z"), null);
		GenerationRecord current = record(FIRST_PACK, hash, Files.size(storage.objectsDirectory().resolve(hash)), Instant.parse("2026-01-02T00:00:00Z"), parent);
		GenerationHistoryIndex completeIndex = GenerationHistoryIndex.fromHistory(FIRST_PACK,
				List.of(new GenerationHistoryEntry(parent.manifest(), parent.metadata()), new GenerationHistoryEntry(current.manifest(), current.metadata())));
		GenerationHistoryIndex compactedIndex = completeIndex.compactBefore(current.metadata().generationId());
		ClientGenerationStore generations = new ClientGenerationStore(storage);

		generations.write(current, GenerationPatchNoteHistory.fromRecords(List.of(parent, current)), completeIndex);
		generations.write(current, GenerationPatchNoteHistory.forRecord(current), compactedIndex);

		assertEquals(completeIndex, generations.historyIndex(current.metadata().generationId()).orElseThrow());
	}

	@Test
	void malformedGeneratedCopyStateRefusesWithoutMutation() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "valid-object");
		GenerationRecord valid = record(FIRST_PACK, hash, Files.size(storage.objectsDirectory().resolve(hash)), Instant.parse("2026-01-01T00:00:00Z"), null);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(valid);
		storage.writeActiveState(FIRST_PACK, valid.metadata().generationId());
		Path malformed = storage.generatedCopiesFile(FIRST_PACK, valid.metadata().generationId(), SELECTION_DIGEST);
		Files.createDirectories(malformed.getParent());
		Files.writeString(malformed, "{}", StandardCharsets.UTF_8);

		assertThrows(IOException.class, generations::compact);

		assertTrue(Files.exists(storage.generationManifest(valid.metadata().generationId())));
		assertTrue(Files.exists(malformed));
	}

	@Test
	void activeTransactionRefusesCompactionWithoutMutation() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "valid-object");
		GenerationRecord valid = record(FIRST_PACK, hash, Files.size(storage.objectsDirectory().resolve(hash)), Instant.parse("2026-01-01T00:00:00Z"), null);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(valid);
		storage.writeActiveState(FIRST_PACK, valid.metadata().generationId());
		Files.writeString(storage.transactionFile(), "active", StandardCharsets.UTF_8);

		IOException error = assertThrows(IOException.class, generations::compact);

		assertTrue(error.getMessage().contains("update transaction is active"));
		assertTrue(Files.exists(storage.generationManifest(valid.metadata().generationId())));
		assertTrue(Files.exists(storage.transactionFile()));
	}

	@Test
	void forgetModpackDeletesOnlyTheInactivePacksRetainedState() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		String secondHash = store(storage, "second-object");
		GenerationRecord first = record(FIRST_PACK, firstHash, Files.size(storage.objectsDirectory().resolve(firstHash)), Instant.parse("2026-01-01T00:00:00Z"), null);
		GenerationRecord second = record(SECOND_PACK, secondHash, Files.size(storage.objectsDirectory().resolve(secondHash)), Instant.parse("2026-01-01T00:00:00Z"), null);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(first);
		generations.write(second);
		storage.writeActiveState(SECOND_PACK, second.metadata().generationId());
		Path overlay = storage.overlayFile(FIRST_PACK, "config/options.txt");
		Files.createDirectories(overlay.getParent());
		Files.writeString(overlay, "edit", StandardCharsets.UTF_8);
		Files.createDirectories(storage.connectionDirectory(FIRST_PACK));
		Files.writeString(storage.connectionDirectory(FIRST_PACK).resolve("connection.json"), "{}", StandardCharsets.UTF_8);

		generations.forgetModpack(FIRST_PACK);

		assertTrue(generations.read(first.metadata().generationId()).isEmpty());
		assertEquals(second, generations.read(second.metadata().generationId()).orElseThrow());
		assertFalse(Files.exists(storage.overlayDirectory(FIRST_PACK)));
		assertFalse(Files.exists(storage.connectionDirectory(FIRST_PACK)));
		assertFalse(Files.exists(storage.objectsDirectory().resolve(firstHash)));
		assertTrue(Files.exists(storage.objectsDirectory().resolve(secondHash)));
		assertEquals(second.metadata().generationId(), storage.readActiveState().generationId);
	}

	@Test
	void preservesOverlaysBaselinesQuarantineAndLocalFiles() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "record-object");
		long size = Files.size(storage.objectsDirectory().resolve(hash));
		GenerationRecord old = record(FIRST_PACK, hash, size, Instant.parse("2026-01-01T00:00:00Z"), null);
		GenerationRecord newest = record(FIRST_PACK, hash, size, Instant.parse("2026-01-02T00:00:00Z"), old);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(old);
		generations.write(newest);
		storage.writeActiveState(FIRST_PACK, newest.metadata().generationId());

		Path overlay = storage.overlayFile(FIRST_PACK, "config/options.txt");
		Files.createDirectories(overlay.getParent());
		Files.writeString(overlay, "player-edit", StandardCharsets.UTF_8);
		ClientStorageJsons.ClientBaselineFields baseline = new ClientStorageJsons.ClientBaselineFields();
		baseline.modpackId = FIRST_PACK;
		ClientStorageJsons.ClientBaselineFields.EntryFields baselineEntry = new ClientStorageJsons.ClientBaselineFields.EntryFields();
		baselineEntry.logicalPath = "mods/local.jar";
		baselineEntry.absent = true;
		baselineEntry.objectHash = "";
		baselineEntry.size = -1;
		baseline.entries = List.of(baselineEntry);
		ConfigTools.writeAtomic(storage.baselineFile(FIRST_PACK), baseline);
		Path local = Files.writeString(storage.gamePath("mods/local.jar"), "local", StandardCharsets.UTF_8);
		Path quarantineSource = Files.writeString(storage.gamePath("mods/quarantined.jar"), "quarantined", StandardCharsets.UTF_8);
		String quarantineHash = HashUtils.getHash(quarantineSource);
		PreservationVault.preserveConflict(storage, old.metadata().generationId(), new UpdatePlan.Conflict(FIRST_PACK, "a".repeat(40), Set.of("test"), "mods/quarantined.jar",
				quarantineHash, Files.size(quarantineSource), "mods/server.jar", "b".repeat(40), 1, UpdatePlan.ConflictAction.PRESERVE_LOCAL));

		generations.compact();

		assertEquals("player-edit", Files.readString(overlay, StandardCharsets.UTF_8));
		assertTrue(Files.exists(storage.baselineFile(FIRST_PACK)));
		assertEquals(1, PreservationVault.read(storage, FIRST_PACK).claims().size());
		assertTrue(Files.exists(storage.objectsDirectory().resolve(quarantineHash)));
		assertTrue(Files.exists(local));
		assertFalse(Files.exists(quarantineSource));
	}

	private ClientStorage storage() throws IOException {
		Path game = temporaryDirectory.resolve("game");
		Files.createDirectories(game.resolve("automodpack"));
		StorageJsons.DataRootFields dataRoot = new StorageJsons.DataRootFields();
		dataRoot.root = temporaryDirectory.resolve("data").toString();
		dataRoot.shared = false;
		ConfigTools.writeAtomic(game.resolve("automodpack/data-root.json"), dataRoot);
		ClientStorage storage = ClientStorage.fromGameDirectory(game);
		storage.ensureRoots();
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private static GenerationRecord record(String modpackId, String hash, long size, Instant createdAt, GenerationRecord parent) {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(size, "mod", false, false, hash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "", "", "", true, true, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("mods/test.jar", file)));
		return GenerationRecord.create(new GroupManifest(modpackId, "Test", "", "", "", "", new TreeMap<>(Map.of("main", group))), parent, createdAt, "notes");
	}

	private static String store(ClientStorage storage, String content) throws IOException {
		Path temporary = Files.createTempFile(storage.objectsDirectory(), ".object-", ".tmp");
		Files.writeString(temporary, content, StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(temporary);
		Files.move(temporary, storage.objectsDirectory().resolve(hash), StandardCopyOption.REPLACE_EXISTING);
		return hash;
	}
}
