package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.utils.HashUtils;

class ClientGenerationStoreTest {
	private static final String FIRST_PACK = "abc1234";
	private static final String SECOND_PACK = "def5678";

	@TempDir
	Path temporaryDirectory;

	@Test
	void reconstructsTheActiveTargetFromMirrorCasAndActiveState() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "projection-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);

		assertTrue(new ClientGenerationStore(storage).activeDocument().isEmpty());

		storage.writeActiveState(FIRST_PACK, document.contentToken(), document.ownershipLedger().toFields());
		new ClientSelectionStore(storage.selectionFile()).compareAndSet(FIRST_PACK, null, new SelectionIntent(Set.of("main")));
		SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.LINUX).orElseThrow();

		assertEquals(document, target.document());
		assertEquals(new SelectionIntent(Set.of("main")), target.selection().intent());
		assertEquals(document.contentToken(), target.flatTarget().contentToken);
		assertEquals(document.ownershipLedger(), target.document().ownershipLedger());
	}

	@Test
	void reconstructsTheActiveDefaultTargetWithoutStoredSelection() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "default-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		storage.writeActiveState(FIRST_PACK, document.contentToken(), document.ownershipLedger().toFields());

		SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.LINUX).orElseThrow();

		assertEquals(document, target.document());
		assertNull(target.expectedPriorIntent());
		assertEquals(Set.of("main"), target.selection().selectedGroups());
	}

	@Test
	void replaysTheExactLedgerForAFullyWitnessedHistory() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), Instant.parse("2026-01-01T00:00:00Z"));
		String secondHash = store(storage, "second-object");
		PackDocument second = document(FIRST_PACK, secondHash, Files.size(storage.objectFile(secondHash)), Instant.parse("2026-01-02T00:00:00Z"), first);
		TestPacks.stageGeneration(storage, first);
		TestPacks.stageGeneration(storage, second);
		storage.writeActiveState(FIRST_PACK, first.contentToken(), first.ownershipLedger().toFields());

		PackDocument newest = new ClientGenerationStore(storage).newestDocument(FIRST_PACK);
		assertEquals(second.contentToken(), newest.contentToken());
		assertEquals(second.ownershipLedger(), newest.ownershipLedger());
	}

	@Test
	void rebuildsARollbackTargetDocumentFromItsMirrorEntry() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), Instant.parse("2026-01-01T00:00:00Z"));
		String secondHash = store(storage, "second-object");
		PackDocument second = document(FIRST_PACK, secondHash, Files.size(storage.objectFile(secondHash)), Instant.parse("2026-01-02T00:00:00Z"), first);
		TestPacks.stageGeneration(storage, first);
		TestPacks.stageGeneration(storage, second);
		storage.writeActiveState(FIRST_PACK, second.contentToken(), second.ownershipLedger().toFields());
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		List<JournalEntry> entries = new JournalMirror(storage).entries(FIRST_PACK);

		PackDocument rollback = generations.document(FIRST_PACK, entries.get(0));
		PackDocument active = generations.document(FIRST_PACK, entries.get(1));

		assertEquals(first.contentToken(), rollback.contentToken());
		assertEquals(first.createdAt(), rollback.createdAt());
		assertEquals(first.ownershipLedger(), rollback.ownershipLedger(), "the replay of a fully witnessed history is the generation's own ledger");
		assertEquals(second.contentToken(), active.contentToken());
		assertEquals(second.ownershipLedger(), active.ownershipLedger(), "the active generation keeps its exact ledger");
	}

	@Test
	void marksARollbackTargetRestorableOnlyWhenEveryTreeObjectIsAvailableLocally() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), Instant.parse("2026-01-01T00:00:00Z"));
		String secondHash = store(storage, "second-object");
		PackDocument second = document(FIRST_PACK, secondHash, Files.size(storage.objectFile(secondHash)), Instant.parse("2026-01-02T00:00:00Z"), first);
		TestPacks.stageGeneration(storage, first);
		TestPacks.stageGeneration(storage, second);
		storage.writeActiveState(FIRST_PACK, second.contentToken(), second.ownershipLedger().toFields());
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		List<JournalEntry> entries = new JournalMirror(storage).entries(FIRST_PACK);

		assertTrue(generations.locallyRestorable(FIRST_PACK, entries.get(0)));
		assertTrue(generations.locallyRestorable(FIRST_PACK, entries.get(1)));

		Files.delete(storage.objectFile(firstHash));
		assertFalse(generations.locallyRestorable(FIRST_PACK, entries.get(0)), "a missing object with no live copy is not restorable");

		Files.createDirectories(storage.gamePath("mods/test.jar").getParent());
		Files.writeString(storage.gamePath("mods/test.jar"), "first-object", StandardCharsets.UTF_8);
		assertTrue(generations.locallyRestorable(FIRST_PACK, entries.get(0)), "the acquisition chain absorbs the object from its live path");

		Files.delete(storage.objectFile(first.policySha1()));
		assertFalse(generations.locallyRestorable(FIRST_PACK, entries.get(0)), "a generation whose policy object is gone cannot be restored");
	}

	@Test
	void reconstructionFailsLoudlyWhenThePolicyObjectIsMissing() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "projection-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		storage.writeActiveState(FIRST_PACK, document.contentToken(), document.ownershipLedger().toFields());
		Files.delete(storage.objectFile(document.policySha1()));

		assertThrows(IOException.class, () -> new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.LINUX));
	}

	@Test
	void forgetModpackDeletesOnlyTheInactivePacksRetainedState() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		String secondHash = store(storage, "second-object");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), TestPacks.CREATED);
		PackDocument second = document(SECOND_PACK, secondHash, Files.size(storage.objectFile(secondHash)), TestPacks.CREATED);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		TestPacks.stageGeneration(storage, first);
		TestPacks.stageGeneration(storage, second);
		new ClientSelectionStore(storage.selectionFile()).compareAndSet(FIRST_PACK, null, new SelectionIntent(Set.of("main")));
		storage.writeActiveState(SECOND_PACK, second.contentToken(), second.ownershipLedger().toFields());
		Path overlay = storage.overlayFile(FIRST_PACK, "config/options.txt");
		Files.createDirectories(overlay.getParent());
		Files.writeString(overlay, "edit", StandardCharsets.UTF_8);
		Files.createDirectories(storage.connectionDirectory(FIRST_PACK));
		Files.writeString(storage.connectionDirectory(FIRST_PACK).resolve("connection.json"), "{}", StandardCharsets.UTF_8);

		generations.forgetModpack(FIRST_PACK);

		assertEquals(List.of(SECOND_PACK), generations.installedPackIds());
		assertFalse(Files.exists(storage.overlayDirectory(FIRST_PACK)));
		assertFalse(Files.exists(storage.connectionDirectory(FIRST_PACK)));
		assertTrue(new ClientSelectionStore(storage.selectionFile()).get(FIRST_PACK).isEmpty());
		assertFalse(Files.exists(storage.objectFile(firstHash)));
		assertTrue(Files.exists(storage.objectFile(secondHash)));
		assertNotNull(generations.newestDocument(SECOND_PACK));
		assertEquals(second.contentToken(), storage.readActiveState().contentToken);
		assertThrows(IOException.class, () -> generations.forgetModpack(SECOND_PACK));
	}

	@Test
	void listingSkipsPacksWhoseGenerationCannotBeReconstructed() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		String secondHash = store(storage, "second-object");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), TestPacks.CREATED);
		PackDocument second = document(SECOND_PACK, secondHash, Files.size(storage.objectFile(secondHash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, first);
		TestPacks.stageGeneration(storage, second);
		Files.delete(storage.objectFile(first.policySha1()));

		assertEquals(List.of(FIRST_PACK, SECOND_PACK), new ClientGenerationStore(storage).installedPackIds());
		assertThrows(IOException.class, () -> new ClientGenerationStore(storage).newestDocument(FIRST_PACK));
		assertEquals(second, new ClientGenerationStore(storage).newestDocument(SECOND_PACK));
	}

	@Test
	void decliningAnAdvanceDetachesUntilTheHeadCatchesUpWithTheActiveGeneration() throws Exception {
		ClientStorage storage = storage();
		String activeHash = store(storage, "active-object");
		String newerHash = store(storage, "newer-object");
		PackDocument active = document(FIRST_PACK, activeHash, Files.size(storage.objectFile(activeHash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, active);
		storage.writeActiveState(FIRST_PACK, active.contentToken(), active.ownershipLedger().toFields());
		ClientGenerationStore generations = new ClientGenerationStore(storage);

		generations.detachOnDeclinedAdvance(FIRST_PACK, active.contentToken());
		assertFalse(generations.isDetached(FIRST_PACK));

		generations.detachOnDeclinedAdvance(FIRST_PACK, newerHash);
		assertTrue(generations.isDetached(FIRST_PACK));

		generations.observeHeadToken(FIRST_PACK, newerHash);
		assertTrue(generations.isDetached(FIRST_PACK));

		generations.observeHeadToken(FIRST_PACK, active.contentToken());
		assertFalse(generations.isDetached(FIRST_PACK));
	}

	@Test
	void detachmentSurvivesRepublishingTheSamePackAndNeverLeavesTheActiveState() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		String secondHash = store(storage, "second-object");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), TestPacks.CREATED);
		PackDocument second = document(SECOND_PACK, secondHash, Files.size(storage.objectFile(secondHash)), TestPacks.CREATED);
		ClientGenerationStore generations = new ClientGenerationStore(storage);

		generations.detachOnDeclinedAdvance(FIRST_PACK, firstHash);
		assertFalse(generations.isDetached(FIRST_PACK), "a pack without an active state cannot detach");

		TestPacks.stageGeneration(storage, first);
		storage.writeActiveState(FIRST_PACK, first.contentToken(), first.ownershipLedger().toFields());
		generations.detachOnDeclinedAdvance(FIRST_PACK, secondHash);
		assertTrue(generations.isDetached(FIRST_PACK));

		storage.writeActiveState(FIRST_PACK, first.contentToken(), first.ownershipLedger().toFields());
		assertTrue(generations.isDetached(FIRST_PACK), "committing the same pack preserves its sovereignty");

		generations.detachOnDeclinedAdvance(SECOND_PACK, firstHash);
		assertFalse(generations.isDetached(SECOND_PACK), "the flag belongs to the active pack only");

		storage.writeActiveState(SECOND_PACK, second.contentToken(), second.ownershipLedger().toFields());
		assertFalse(generations.isDetached(FIRST_PACK));
		assertFalse(generations.isDetached(SECOND_PACK));
	}

	@Test
	void compactionKeepsActiveNewestAndPoliciesAndTrimsOnlyOlderGenerations() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		String secondHash = store(storage, "second-object");
		String thirdHash = store(storage, "third-object");
		String orphanHash = store(storage, "orphan");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), Instant.parse("2026-01-01T00:00:00Z"));
		PackDocument second = document(FIRST_PACK, secondHash, Files.size(storage.objectFile(secondHash)), Instant.parse("2026-01-02T00:00:00Z"), first);
		PackDocument third = document(FIRST_PACK, thirdHash, Files.size(storage.objectFile(thirdHash)), Instant.parse("2026-01-03T00:00:00Z"), second);
		TestPacks.stageGeneration(storage, first);
		TestPacks.stageGeneration(storage, second);
		TestPacks.stageGeneration(storage, third);
		storage.writeActiveState(FIRST_PACK, first.contentToken(), first.ownershipLedger().toFields());
		Files.createDirectories(storage.overlayFile(FIRST_PACK, "config/options.txt").getParent());
		Files.writeString(storage.overlayFile(FIRST_PACK, "config/options.txt"), "overlay-object", StandardCharsets.UTF_8);
		String overlayHash = HashUtils.sha1("overlay-object".getBytes(StandardCharsets.UTF_8));
		byte[] overlayObject = "overlay-object".getBytes(StandardCharsets.UTF_8);
		ClientObjectStore.storeObject(storage, overlayHash, overlayObject);
		String selectionDigest = HashUtils.sha1("selection".getBytes(StandardCharsets.UTF_8));
		new GeneratedCopyState(FIRST_PACK, second.contentToken(), selectionDigest, List.of(new GeneratedCopyState.Entry("mods/trimmed.jar", secondHash, Files.size(storage.objectFile(secondHash))))).write(storage);
		new GeneratedCopyState(FIRST_PACK, third.contentToken(), selectionDigest, List.of(new GeneratedCopyState.Entry("mods/kept.jar", thirdHash, Files.size(storage.objectFile(thirdHash))))).write(storage);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		List<JournalEntry> entries = new JournalMirror(storage).entries(FIRST_PACK);
		byte[] mirrorBefore = Files.readAllBytes(storage.historyJournalFile(FIRST_PACK));
		for (JournalEntry entry : entries) assertTrue(generations.locallyRestorable(FIRST_PACK, entry));

		ClientGenerationStore.CompactionResult result = generations.compact();

		assertTrue(Files.exists(storage.objectFile(firstHash)), "the active generation's content stays");
		assertTrue(Files.exists(storage.objectFile(thirdHash)), "the newest generation's content stays");
		assertTrue(Files.exists(storage.objectFile(first.policySha1())) && Files.exists(storage.objectFile(second.policySha1())) && Files.exists(storage.objectFile(third.policySha1())),
				"every policy document stays");
		assertTrue(Files.exists(storage.objectFile(overlayHash)), "overlay pins stay");
		assertTrue(Files.exists(storage.generatedCopiesFile(FIRST_PACK, third.contentToken(), selectionDigest)), "kept generations keep their generated-copy state");
		assertFalse(Files.exists(storage.generatedCopiesFile(FIRST_PACK, second.contentToken(), selectionDigest)), "trimmed generations lose their generated-copy state");
		assertFalse(Files.exists(storage.objectFile(secondHash)), "a trimmed generation's content is reclaimed");
		assertFalse(Files.exists(storage.objectFile(orphanHash)), "unreferenced objects are reclaimed too");
		assertEquals(2, result.collection().deletedObjectCount());
		assertEquals("second-object".getBytes(StandardCharsets.UTF_8).length + "orphan".getBytes(StandardCharsets.UTF_8).length, result.collection().deletedObjectBytes());
		assertArrayEquals(mirrorBefore, Files.readAllBytes(storage.historyJournalFile(FIRST_PACK)), "the mirror file is byte-identical");
		assertEquals(first.contentToken(), storage.readActiveState().contentToken);
		assertTrue(Files.exists(storage.overlayFile(FIRST_PACK, "config/options.txt")));
		assertTrue(generations.locallyRestorable(FIRST_PACK, entries.get(0)));
		assertFalse(generations.locallyRestorable(FIRST_PACK, entries.get(1)), "a trimmed generation is no longer restorable");
		assertTrue(generations.locallyRestorable(FIRST_PACK, entries.get(2)));
		ClientGenerationStore.CompactionReceipt receipt = generations.compactionReceipt(FIRST_PACK).orElseThrow();
		assertEquals(FIRST_PACK, receipt.modpackId());
		assertEquals(entries.get(2).seq(), receipt.boundarySeq());
		assertEquals(2, receipt.reclaimedObjectCount());
		assertEquals(result.collection().deletedObjectBytes(), receipt.reclaimedObjectBytes());
	}

	@Test
	void compactionRefusesWhileAnUpdateTransactionIsActive() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "first-object");
		PackDocument first = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		String secondHash = store(storage, "second-object");
		PackDocument second = document(FIRST_PACK, secondHash, Files.size(storage.objectFile(secondHash)), TestPacks.CREATED.plusSeconds(1), first);
		TestPacks.stageGeneration(storage, first);
		TestPacks.stageGeneration(storage, second);
		storage.writeActiveState(FIRST_PACK, second.contentToken(), second.ownershipLedger().toFields());
		Files.writeString(storage.transactionFile(), "{}", StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> new ClientGenerationStore(storage).compact());

		assertTrue(Files.exists(storage.objectFile(hash)), "nothing is reclaimed by a refused compaction");
		assertTrue(new ClientGenerationStore(storage).compactionReceipt(FIRST_PACK).isEmpty());
	}

	@Test
	void compactionReceiptSurvivesUntilOverwrittenAndLeavesWithThePack() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		String secondHash = store(storage, "second-object");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), TestPacks.CREATED);
		PackDocument second = document(FIRST_PACK, secondHash, Files.size(storage.objectFile(secondHash)), TestPacks.CREATED.plusSeconds(1), first);
		TestPacks.stageGeneration(storage, first);
		TestPacks.stageGeneration(storage, second);
		storage.writeActiveState(FIRST_PACK, second.contentToken(), second.ownershipLedger().toFields());
		ClientGenerationStore generations = new ClientGenerationStore(storage);

		ClientGenerationStore.CompactionReceipt receipt = generations.compact().receipts().get(0);
		assertEquals(1, receipt.reclaimedObjectCount(), "the inactive generation's content is reclaimed");
		assertEquals(receipt, generations.compactionReceipt(FIRST_PACK).orElseThrow());

		ClientGenerationStore.CompactionReceipt overwritten = generations.compact().receipts().get(0);
		assertEquals(0, overwritten.reclaimedObjectCount(), "a second compaction has nothing left to reclaim");
		assertEquals(receipt.boundarySeq(), overwritten.boundarySeq());
		assertEquals(overwritten, generations.compactionReceipt(FIRST_PACK).orElseThrow());

		storage.clearActiveState();
		generations.forgetModpack(FIRST_PACK);
		assertTrue(generations.compactionReceipt(FIRST_PACK).isEmpty(), "the receipt is deleted with the pack");
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private static PackDocument document(String modpackId, String hash, long size, Instant createdAt) {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(size, "mod", false, hash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "", "", true, true, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("mods/test.jar", file)));
		GroupManifest manifest = new GroupManifest(modpackId, "Test", "", "", "", "", new TreeMap<>(Map.of("main", group)));
		return PackDocument.create(manifest, TestPacks.policySha1(manifest), createdAt, null);
	}

	private static PackDocument document(String modpackId, String hash, long size, Instant createdAt, PackDocument parent) {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(size, "mod", false, hash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "", "", true, true, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("mods/test.jar", file)));
		GroupManifest manifest = new GroupManifest(modpackId, "Test", "", "", "", "", new TreeMap<>(Map.of("main", group)));
		return PackDocument.create(manifest, TestPacks.policySha1(manifest), createdAt, parent.ownershipLedger());
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
