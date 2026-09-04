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
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.utils.HashUtils;

class ClientGenerationStoreTest {
	private static final String FIRST_PACK = "abc1234";
	private static final String SECOND_PACK = "def5678";
	private static final String SELECTION_DIGEST = "1".repeat(40);
	private static final Instant FIRST_CREATED = Instant.parse("2026-01-01T00:00:00Z");
	private static final Instant SECOND_CREATED = Instant.parse("2026-01-02T00:00:00Z");

	@TempDir
	Path temporaryDirectory;

	@Test
	void retainsNewestRecordForEveryPackAndAnOlderActiveRecord() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-content");
		String middleHash = store(storage, "middle-content");
		String newestHash = store(storage, "newest-content");
		String otherHash = store(storage, "other-content");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), FIRST_CREATED, null);
		PackDocument middle = document(FIRST_PACK, middleHash, Files.size(storage.objectFile(middleHash)), SECOND_CREATED, first);
		PackDocument newest = document(FIRST_PACK, newestHash, Files.size(storage.objectFile(newestHash)), Instant.parse("2026-01-03T00:00:00Z"), middle);
		PackDocument other = document(SECOND_PACK, otherHash, Files.size(storage.objectFile(otherHash)), FIRST_CREATED, null);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		for (PackDocument record : List.of(first, middle, newest, other)) generations.write(record);
		storage.writeActiveState(FIRST_PACK, first.contentToken());

		ClientGenerationStore.CompactionResult result = generations.compact();

		assertEquals(List.of(first.contentToken(), newest.contentToken(), other.contentToken()).stream().sorted().toList(), result.retainedTokens());
		assertEquals(List.of(middle.contentToken()), result.removedTokens());
		assertTrue(Files.exists(storage.generationManifest(first.contentToken())));
		assertTrue(Files.exists(storage.generationManifest(newest.contentToken())));
		assertFalse(Files.exists(storage.generationDirectory(middle.contentToken())));
		assertEquals(3, result.recordCountAfter());
	}

	@Test
	void compactionCollectsReplacedObjectButPreservesHistoricalLedgerMetadata() throws Exception {
		ClientStorage storage = storage();
		String oldContent = "old-object";
		String currentContent = "current-object";
		String oldHash = store(storage, oldContent);
		String currentHash = store(storage, currentContent);
		PackDocument old = document(FIRST_PACK, oldHash, oldContent.getBytes(StandardCharsets.UTF_8).length, FIRST_CREATED, null);
		PackDocument current = document(FIRST_PACK, currentHash, currentContent.getBytes(StandardCharsets.UTF_8).length, SECOND_CREATED, old);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(old);
		generations.write(current);
		storage.writeActiveState(FIRST_PACK, current.contentToken());

		ClientGenerationStore.CompactionResult result = generations.compact();

		assertEquals(1, result.objectCollection().deletedObjectCount());
		assertFalse(Files.exists(storage.objectFile(oldHash)));
		assertTrue(Files.exists(storage.objectFile(currentHash)));
		assertDoesNotThrow(() -> ClientObjectStore.validate(storage));
		PackDocument retained = generations.read(current.contentToken()).orElseThrow();
		assertTrue(retained.ownershipLedger().entries().get("mods/test.jar").historicalHashes().stream()
				.anyMatch(content -> content.sha1().equals(oldHash) && content.size() == oldContent.getBytes(StandardCharsets.UTF_8).length));
	}

	@Test
	void deactivatedPackCompactsWhenUnselectedCatalogueObjectWasNeverCached() throws Exception {
		ClientStorage storage = storage();
		String selectedHash = store(storage, "selected-object");
		String orphanHash = store(storage, "orphan-object");
		String uncachedOptionalHash = "f".repeat(40);
		GroupManifest.GroupFile selectedFile = new GroupManifest.GroupFile(Files.size(storage.objectFile(selectedHash)), "mod", false,
				selectedHash, null);
		GroupManifest.GroupFile optionalFile = new GroupManifest.GroupFile(176, "config", false, uncachedOptionalHash, null);
		GroupManifest.Group selectedGroup = new GroupManifest.Group("Core", "", "", true, true, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("mods/test.jar", selectedFile)));
		GroupManifest.Group optionalGroup = new GroupManifest.Group("Optional", "", "", false, false, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("config/optional.json", optionalFile)));
		PackDocument record = TestPacks.document(new GroupManifest(FIRST_PACK, "Test", "", "", "", "",
				new TreeMap<>(Map.of("main", selectedGroup, "optional", optionalGroup))));
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(record);
		new ClientSelectionStore(storage.selectionFile()).compareAndSet(FIRST_PACK, null, new SelectionIntent(Set.of()));
		storage.writeActiveState(FIRST_PACK, record.contentToken());
		storage.clearActiveState();

		ClientGenerationStore.CompactionResult result = generations.compact();

		assertEquals(1, result.objectCollection().deletedObjectCount());
		assertTrue(Files.exists(storage.objectFile(selectedHash)));
		assertFalse(Files.exists(storage.objectFile(orphanHash)));
		assertFalse(Files.exists(storage.objectFile(uncachedOptionalHash)));
	}

	@Test
	void removesGeneratedCopiesOnlyForRemovedGenerations() throws Exception {
		ClientStorage storage = storage();
		String oldRecordHash = store(storage, "old-record-object");
		String newestRecordHash = store(storage, "newest-record-object");
		String oldGeneratedHash = store(storage, "old-generated-object");
		String newGeneratedHash = store(storage, "new-generated-object");
		PackDocument old = document(FIRST_PACK, oldRecordHash, Files.size(storage.objectFile(oldRecordHash)), FIRST_CREATED, null);
		PackDocument newest = document(FIRST_PACK, newestRecordHash, Files.size(storage.objectFile(newestRecordHash)), SECOND_CREATED, old);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(old);
		generations.write(newest);
		storage.writeActiveState(FIRST_PACK, newest.contentToken());
		new GeneratedCopyState(FIRST_PACK, old.contentToken(), SELECTION_DIGEST,
				List.of(new GeneratedCopyState.Entry("mods/old-generated.jar", oldGeneratedHash, Files.size(storage.objectFile(oldGeneratedHash))))).write(storage);
		new GeneratedCopyState(FIRST_PACK, newest.contentToken(), SELECTION_DIGEST,
				List.of(new GeneratedCopyState.Entry("mods/new-generated.jar", newGeneratedHash, Files.size(storage.objectFile(newGeneratedHash))))).write(storage);

		ClientGenerationStore.CompactionResult result = generations.compact();

		assertEquals(2, result.generatedCopyCountBefore());
		assertEquals(1, result.generatedCopyCountAfter());
		assertFalse(Files.exists(storage.generatedCopiesGenerationDirectory(FIRST_PACK, old.contentToken())));
		assertTrue(Files.exists(storage.generatedCopiesFile(FIRST_PACK, newest.contentToken(), SELECTION_DIGEST)));
		assertTrue(Files.exists(storage.objectFile(newGeneratedHash)));
	}

	@Test
	void resumesCompactionAfterGenerationDeletion() throws Exception {
		ClientStorage storage = storage();
		String oldRecordHash = store(storage, "old-record-object");
		String newestRecordHash = store(storage, "newest-record-object");
		String generatedHash = store(storage, "generated-object");
		PackDocument old = document(FIRST_PACK, oldRecordHash, Files.size(storage.objectFile(oldRecordHash)), FIRST_CREATED, null);
		PackDocument newest = document(FIRST_PACK, newestRecordHash, Files.size(storage.objectFile(newestRecordHash)), SECOND_CREATED, old);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(old);
		generations.write(newest);
		storage.writeActiveState(FIRST_PACK, newest.contentToken());
		new GeneratedCopyState(FIRST_PACK, old.contentToken(), SELECTION_DIGEST,
				List.of(new GeneratedCopyState.Entry("mods/generated.jar", generatedHash, Files.size(storage.objectFile(generatedHash))))).write(storage);
		ClientStorageJsons.ClientCompactionJournalFields journal = new ClientStorageJsons.ClientCompactionJournalFields();
		journal.removedGenerationIds = List.of(old.contentToken());
		ConfigTools.writeAtomic(storage.compactionJournalFile(), journal);
		Files.delete(storage.generationManifest(old.contentToken()));

		generations.recoverCompaction();

		assertFalse(Files.exists(storage.compactionJournalFile()));
		assertFalse(Files.exists(storage.generatedCopiesGenerationDirectory(FIRST_PACK, old.contentToken())));
		assertFalse(Files.exists(storage.objectFile(generatedHash)));
		assertTrue(Files.exists(storage.objectFile(newestRecordHash)));
	}

	@Test
	void malformedRecordRefusesWithoutMutation() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "valid-object");
		PackDocument valid = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), FIRST_CREATED, null);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(valid);
		storage.writeActiveState(FIRST_PACK, valid.contentToken());
		Path local = Files.writeString(storage.gamePath("mods/local.jar"), "local", StandardCharsets.UTF_8);
		String malformedId = "0".repeat(40);
		Files.createDirectories(storage.generationDirectory(malformedId));
		Files.writeString(storage.generationManifest(malformedId), "{}", StandardCharsets.UTF_8);

		assertThrows(IOException.class, generations::compact);

		assertTrue(Files.exists(storage.generationManifest(valid.contentToken())));
		assertTrue(Files.exists(storage.generationManifest(malformedId)));
		assertTrue(Files.exists(local));
		assertEquals(valid.contentToken(), storage.readActiveState().contentToken);
	}

	@Test
	void rewritingTheSameRecordKeepsOneDeterministicRecord() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "generation-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), FIRST_CREATED, null);
		ClientGenerationStore generations = new ClientGenerationStore(storage);

		generations.write(document);
		generations.write(document);

		assertEquals(document, generations.read(document.contentToken()).orElseThrow());
		assertDoesNotThrow(() -> generations.write(document));
	}

	@Test
	void conflictingRecordForTheSameTokenRefusesWithoutMutation() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "generation-object");
		PackDocument stored = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), FIRST_CREATED, null);
		PackDocument conflicting = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), SECOND_CREATED, null);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(stored);

		assertThrows(IOException.class, () -> generations.write(conflicting));

		assertEquals(stored, generations.read(stored.contentToken()).orElseThrow());
	}

	@Test
	void malformedGeneratedCopyStateRefusesWithoutMutation() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "valid-object");
		PackDocument valid = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), FIRST_CREATED, null);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(valid);
		storage.writeActiveState(FIRST_PACK, valid.contentToken());
		Path malformed = storage.generatedCopiesFile(FIRST_PACK, valid.contentToken(), SELECTION_DIGEST);
		Files.createDirectories(malformed.getParent());
		Files.writeString(malformed, "{}", StandardCharsets.UTF_8);

		assertThrows(IOException.class, generations::compact);

		assertTrue(Files.exists(storage.generationManifest(valid.contentToken())));
		assertTrue(Files.exists(malformed));
	}

	@Test
	void activeTransactionRefusesCompactionWithoutMutation() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "valid-object");
		PackDocument valid = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), FIRST_CREATED, null);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(valid);
		storage.writeActiveState(FIRST_PACK, valid.contentToken());
		Files.writeString(storage.transactionFile(), "active", StandardCharsets.UTF_8);

		IOException error = assertThrows(IOException.class, generations::compact);

		assertTrue(error.getMessage().contains("update transaction is active"));
		assertTrue(Files.exists(storage.generationManifest(valid.contentToken())));
		assertTrue(Files.exists(storage.transactionFile()));
	}

	@Test
	void forgetModpackDeletesOnlyTheInactivePacksRetainedState() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		String secondHash = store(storage, "second-object");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), FIRST_CREATED, null);
		PackDocument second = document(SECOND_PACK, secondHash, Files.size(storage.objectFile(secondHash)), FIRST_CREATED, null);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(first);
		generations.write(second);
		new ClientSelectionStore(storage.selectionFile()).compareAndSet(FIRST_PACK, null, new SelectionIntent(Set.of("main")));
		storage.writeActiveState(SECOND_PACK, second.contentToken());
		Path overlay = storage.overlayFile(FIRST_PACK, "config/options.txt");
		Files.createDirectories(overlay.getParent());
		Files.writeString(overlay, "edit", StandardCharsets.UTF_8);
		Files.createDirectories(storage.connectionDirectory(FIRST_PACK));
		Files.writeString(storage.connectionDirectory(FIRST_PACK).resolve("connection.json"), "{}", StandardCharsets.UTF_8);
		Path firstMirror = storage.historyJournalFile(FIRST_PACK);
		Files.createDirectories(firstMirror.getParent());
		Files.writeString(firstMirror, "{}", StandardCharsets.UTF_8);
		Path secondMirror = storage.historyJournalFile(SECOND_PACK);
		Files.createDirectories(secondMirror.getParent());
		Files.writeString(secondMirror, "{}", StandardCharsets.UTF_8);

		generations.forgetModpack(FIRST_PACK);

		assertTrue(generations.read(first.contentToken()).isEmpty());
		assertEquals(second, generations.read(second.contentToken()).orElseThrow());
		assertFalse(Files.exists(storage.overlayDirectory(FIRST_PACK)));
		assertFalse(Files.exists(storage.connectionDirectory(FIRST_PACK)));
		assertFalse(Files.exists(firstMirror));
		assertTrue(Files.exists(secondMirror));
		assertFalse(Files.exists(storage.objectFile(firstHash)));
		assertTrue(Files.exists(storage.objectFile(secondHash)));
		assertTrue(new ClientSelectionStore(storage.selectionFile()).get(FIRST_PACK).isEmpty());
		assertEquals(second.contentToken(), storage.readActiveState().contentToken);
	}

	@Test
	void preservesOverlaysBaselinesQuarantineAndLocalFiles() throws Exception {
		ClientStorage storage = storage();
		String oldHash = store(storage, "old-record-object");
		String newestHash = store(storage, "newest-record-object");
		PackDocument old = document(FIRST_PACK, oldHash, Files.size(storage.objectFile(oldHash)), FIRST_CREATED, null);
		PackDocument newest = document(FIRST_PACK, newestHash, Files.size(storage.objectFile(newestHash)), SECOND_CREATED, old);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(old);
		generations.write(newest);
		storage.writeActiveState(FIRST_PACK, newest.contentToken());

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
		PreservationVault.preserveConflict(storage, "c".repeat(40), new UpdatePlan.Conflict(FIRST_PACK, "a".repeat(40), Set.of("test"), "mods/quarantined.jar",
				quarantineHash, Files.size(quarantineSource), "mods/server.jar", "b".repeat(40), 1, UpdatePlan.ConflictAction.PRESERVE_LOCAL));

		generations.compact();

		assertEquals("player-edit", Files.readString(overlay, StandardCharsets.UTF_8));
		assertTrue(Files.exists(storage.baselineFile(FIRST_PACK)));
		assertEquals(1, PreservationVault.read(storage, FIRST_PACK).claims().size());
		assertTrue(Files.exists(storage.objectFile(quarantineHash)));
		assertTrue(Files.exists(local));
		assertFalse(Files.exists(quarantineSource));
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private static PackDocument document(String modpackId, String hash, long size, Instant createdAt, PackDocument parent) {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(size, "mod", false, hash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "", "", true, true, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("mods/test.jar", file)));
		GroupManifest manifest = new GroupManifest(modpackId, "Test", "", "", "", "", new TreeMap<>(Map.of("main", group)));
		return PackDocument.create(manifest, TestPacks.policySha1(manifest), createdAt, parent == null ? null : parent.ownershipLedger());
	}

	private static String store(ClientStorage storage, String content) throws IOException {
		Path temporary = Files.createTempFile(storage.objectsDirectory(), ".object-", ".tmp");
		Files.writeString(temporary, content, StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(temporary);
		Path destination = storage.objectFile(hash);
		Files.createDirectories(destination.getParent());
		Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
		return hash;
	}
}
