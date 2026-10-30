package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.modpack.generation.Journal;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;

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

		storage.writeActiveState(document);
		new ClientSelectionStore(storage.selectionFile()).put(FIRST_PACK, new SelectionIntent(Set.of("main")));
		SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.LINUX).orElseThrow();

		assertEquals(document, target.document());
		assertEquals(new SelectionIntent(Set.of("main")), target.selection().intent());
		assertEquals(document.contentToken(), target.flatTarget().contentToken);
		assertEquals(document.ownershipLedger(), target.document().ownershipLedger());
	}

	@Test
	void aSavedDesireNeverMovesTheInstalledSelection() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "projection-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		SelectionIntent applied = new SelectionIntent(Set.of("main"));
		storage.writeActiveState(document, applied);

		// The pointer carries the applied answer even when the store's desire has already moved ahead of it.
		new ClientSelectionStore(storage.selectionFile()).put(FIRST_PACK, new SelectionIntent(Set.of("main", "extra")));
		assertEquals(applied, new ClientGenerationStore(storage).appliedSelection(FIRST_PACK).orElseThrow());
		assertEquals(applied, new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.LINUX).orElseThrow().selection().intent());

		// A pointer written before the split falls back to the store, which was the applied record then.
		storage.writeActiveState(document);
		assertEquals(new SelectionIntent(Set.of("main", "extra")), new ClientGenerationStore(storage).appliedSelection(FIRST_PACK).orElseThrow());
	}

	@Test
	void reconstructsTheActiveDefaultTargetWithoutStoredSelection() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "default-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		storage.writeActiveState(document);

		SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.LINUX).orElseThrow();

		assertEquals(document, target.document());
		assertNull(target.expectedPriorIntent());
		assertEquals(Set.of("main"), target.selection().selectedGroups());
	}

	@Test
	void declaringDetachmentFreesTheActiveProjection() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "kept".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(bytes);
		ClientObjectStore.storeObject(storage, hash, bytes);
		Path object = storage.objectFile(hash);
		PackDocument document = document(FIRST_PACK, hash, bytes.length, Instant.now());
		storage.writeActiveState(document);
		Path activeFile = storage.activePath("config/kept.txt");
		Files.createDirectories(activeFile.getParent());
		VerifiedFileTransfer.linkAtomic(object, activeFile, bytes.length, hash, null);
		assertTrue(ImmutableFiles.isProtected(object));
		assertTrue(ImmutableFiles.isProtected(activeFile));

		new ClientGenerationStore(storage).declareDetached(FIRST_PACK);

		assertTrue(storage.isDetached(FIRST_PACK));
		assertTrue(ImmutableFiles.isProtected(object));
		assertFalse(ImmutableFiles.isProtected(activeFile));
		Files.writeString(activeFile, "edited", StandardCharsets.UTF_8);
		assertEquals("kept", Files.readString(object, StandardCharsets.UTF_8));
		Files.delete(activeFile);
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
		storage.writeActiveState(first);

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
		storage.writeActiveState(second);
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
	void aPolicyOnlyRepublishResolvesTheGenerationToTheWitnessedPolicy() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "shared-object");
		long size = Files.size(storage.objectFile(hash));
		PackDocument first = document(FIRST_PACK, hash, size, Instant.parse("2026-01-01T00:00:00Z"));
		PackDocument second = republished(FIRST_PACK, hash, size, Instant.parse("2026-01-02T00:00:00Z"));
		assertEquals(first.contentToken(), second.contentToken(), "the scenario: a policy-only publish keeps the content token");
		assertNotEquals(first.policySha1(), second.policySha1());
		// The client witnessed only the second head, so only the second policy document is in the CAS; the first one never existed locally.
		ClientObjectStore.storeObject(storage, second.policySha1(), ConfigTools.GSON.toJson(second.manifest().toFields()).getBytes(StandardCharsets.UTF_8));
		appendEntry(storage, first);
		appendEntry(storage, second);

		storage.writeActiveState(second);
		new ClientSelectionStore(storage.selectionFile()).put(FIRST_PACK, new SelectionIntent(Set.of("main")));
		SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.LINUX).orElseThrow();

		assertEquals(second.policySha1(), target.document().policySha1());
		assertEquals(second.createdAt(), target.document().createdAt());
	}

	private static PackDocument republished(String modpackId, String hash, long size, Instant createdAt) {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(size, false, hash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "", "General", GroupSelectionMode.REQUIRED, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("mods/test.jar", file)));
		GroupManifest manifest = new GroupManifest(modpackId, "Test republished", "", "", "", "", new TreeMap<>(Map.of("main", group)));
		return PackDocument.create(manifest, TestPacks.policySha1(manifest), createdAt, null);
	}

	private static void appendEntry(ClientStorage storage, PackDocument document) throws IOException {
		Journal journal = Journal.open(storage.historyJournalFile(document.manifest().modpackId()));
		journal.append(new JournalEntry(journal.isEmpty() ? 1 : journal.head().seq() + 1, document.contentToken(), document.policySha1(), document.createdAt(), "",
				JournalEntry.NO_RESTORE, List.of()));
	}

	@Test
	void aWaitingTrackBelongsOnlyToThePolicyGenerationNamedByTheHead() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "shared-object");
		long size = Files.size(storage.objectFile(hash));
		PackDocument first = document(FIRST_PACK, hash, size, Instant.parse("2026-01-01T00:00:00Z"));
		PackDocument second = republished(FIRST_PACK, hash, size, Instant.parse("2026-01-02T00:00:00Z"));
		ClientObjectStore.storeObject(storage, first.policySha1(), ConfigTools.GSON.toJson(first.manifest().toFields()).getBytes(StandardCharsets.UTF_8));
		ClientObjectStore.storeObject(storage, second.policySha1(), ConfigTools.GSON.toJson(second.manifest().toFields()).getBytes(StandardCharsets.UTF_8));
		appendEntry(storage, first);
		appendEntry(storage, second);
		String trackSha1 = store(storage, "waiting-track");
		GenerationJsons.HeadDocumentFields head = TestPacks.head(second.manifest());
		head.policySha1 = second.policySha1();
		head.waitingMusicSha1 = trackSha1;
		ConfigTools.writeAtomic(storage.historyHeadFile(FIRST_PACK), head);

		ClientGenerationStore generations = new ClientGenerationStore(storage);
		List<JournalEntry> entries = new JournalMirror(storage).entries(FIRST_PACK);
		JournalEntry firstEntry = entries.get(0);
		JournalEntry secondEntry = entries.get(1);

		assertEquals("", generations.document(FIRST_PACK, firstEntry).waitingMusicSha1());
		assertEquals(trackSha1, generations.document(FIRST_PACK, secondEntry).waitingMusicSha1());
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
		storage.writeActiveState(second);
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

	/**
	 * The active pointer is the one record of what this client runs, and the content-addressed copy of its policy
	 * document is only a cache. A pointer that cannot be read back because the cache entry went away is a pointer the
	 * boot deletes, and deleting it loses the player's installed modpack over a collectible cache miss.
	 */
	@Test
	void theInstalledGenerationSurvivesLosingItsContentAddressedPolicyCopy() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "projection-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		storage.writeActiveState(document);
		Files.delete(storage.objectFile(document.policySha1()));

		SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.LINUX).orElseThrow();

		assertEquals(document.manifest(), target.document().manifest());
		assertEquals(document.policySha1(), target.document().policySha1());
		assertEquals(document.contentToken(), target.document().contentToken());
	}

	/**
	 * One content token can carry several generations: a policy-only publish changes the policy document while the file
	 * tree, and with it the token, stays put. The pointer is what says which one is installed, and it answers without
	 * reading the object store at all.
	 */
	@Test
	void aRepublishedGenerationUnderTheSameTokenResolvesToWhatThePointerCommitted() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "shared-object");
		long size = Files.size(storage.objectFile(hash));
		PackDocument first = document(FIRST_PACK, hash, size, Instant.parse("2026-01-01T00:00:00Z"));
		PackDocument second = republished(FIRST_PACK, hash, size, Instant.parse("2026-01-02T00:00:00Z"));
		appendEntry(storage, first);
		appendEntry(storage, second);

		storage.writeActiveState(second);
		new ClientSelectionStore(storage.selectionFile()).put(FIRST_PACK, new SelectionIntent(Set.of("main")));

		// The mirror now holds two generations under one token. The pointer names one of them, and the answer has to
		// be that one: pairing its manifest with the other entry's policy hash would be a document that never existed.
		SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.LINUX).orElseThrow();

		assertEquals(second.manifest(), target.document().manifest());
		assertEquals(second.policySha1(), target.document().policySha1());
		assertNotEquals(first.policySha1(), target.document().policySha1(), "precondition: the republication is a different policy document");
	}

	/**
	 * A pointer written by a shipped build carries no manifest, so it has to keep resolving the way it always did:
	 * through the content-addressed copy. This is the only thing standing between an upgrade and a boot that cannot find
	 * its own modpack, so the absence has to be an accepted shape rather than an accident.
	 */
	@Test
	void aPointerWrittenBeforeTheManifestFieldStillResolves() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "projection-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		storage.writeActiveState(document);

		Path pointer = storage.stateFile();
		JsonObject written = JsonParser.parseString(Files.readString(pointer, StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject shipped = written.deepCopy();
		shipped.remove("manifest");
		shipped.remove("manifestSha1");
		assertEquals(2, written.size() - shipped.size(), "precondition: this pointer carries the two fields a shipped one lacks");
		Files.writeString(pointer, shipped.toString(), StandardCharsets.UTF_8);

		SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.LINUX).orElseThrow();

		assertEquals(document.manifest(), target.document().manifest());
	}

	/**
	 * A pointer that carries a manifest for a different generation describes a pack the client never installed, and
	 * nothing downstream would notice: the ledger, the pack, and the token would all still agree. The pointer is unique
	 * state, so this fails the boot in place for a human to look at rather than resolving into a plausible lie.
	 */
	@Test
	void rejectsAPointerWhoseCarriedManifestIsNotItsOwnGeneration() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "projection-object");
		long size = Files.size(storage.objectFile(hash));
		PackDocument document = document(FIRST_PACK, hash, size, TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		storage.writeActiveState(document);

		Path pointer = storage.stateFile();
		JsonObject written = JsonParser.parseString(Files.readString(pointer, StandardCharsets.UTF_8)).getAsJsonObject();
		GroupManifest.GroupFile other = new GroupManifest.GroupFile(size, false, hash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "", "General", GroupSelectionMode.REQUIRED, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("mods/other.jar", other)));
		written.add("manifest", ConfigTools.GSON.toJsonTree(new GroupManifest(FIRST_PACK, "Test", "", "", "", "", new TreeMap<>(Map.of("main", group)))));
		Files.writeString(pointer, written.toString(), StandardCharsets.UTF_8);

		assertThrows(IOException.class, storage::readActiveState);
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
		new ClientSelectionStore(storage.selectionFile()).put(FIRST_PACK, new SelectionIntent(Set.of("main")));
		storage.writeActiveState(second);
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
	void listingListsOnlyConsentedPacksAndStillFailsLoudlyOnCorruptGenerations() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		String secondHash = store(storage, "second-object");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), TestPacks.CREATED);
		PackDocument second = document(SECOND_PACK, secondHash, Files.size(storage.objectFile(secondHash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, first);
		TestPacks.stageGeneration(storage, second);
		ClientGenerationStore generations = new ClientGenerationStore(storage);

		// Both mirrors exist, but neither pack was ever accepted: a fetch alone is cache, never an install.
		assertTrue(generations.installedPackIds().isEmpty());
		Files.delete(storage.objectFile(first.policySha1()));
		assertThrows(IOException.class, () -> generations.newestDocument(FIRST_PACK));

		new ClientSelectionStore(storage.selectionFile()).put(SECOND_PACK, new SelectionIntent(Set.of("main")));
		assertEquals(List.of(SECOND_PACK), new ClientGenerationStore(storage).installedPackIds());
		assertEquals(second, new ClientGenerationStore(storage).newestDocument(SECOND_PACK));
	}

	/**
	 * A generation whose documents are not on this machine and a mirror that is damaged are different events, and the
	 * caller acts on them differently: the first is a pack that has not synced yet, the second is storage the player
	 * has to look at. Collapsing them into one IOException is what let a pack awaiting a sync take the screen down.
	 */
	@Test
	void reportsUnstoredGenerationContentDistinctlyFromADamagedMirror() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "projection-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		Files.delete(storage.objectFile(document.policySha1()));

		MissingGenerationContentException failure = assertThrows(MissingGenerationContentException.class, () -> new ClientGenerationStore(storage).newestDocument(FIRST_PACK));
		assertTrue(failure.getMessage().contains(document.contentToken()), "the failure names the generation that is not stored, not the entry that could not be reached");
		assertFalse(failure.getMessage().contains("never reaches"), "the old message blamed the mirror for content that was simply never fetched");
	}

	@Test
	void newestDocumentSkipsAnUnwitnessedMiddlePolicy() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), Instant.parse("2026-01-01T00:00:00Z"));
		String secondHash = store(storage, "second-object");
		PackDocument second = document(FIRST_PACK, secondHash, Files.size(storage.objectFile(secondHash)), Instant.parse("2026-01-02T00:00:00Z"), first);
		String thirdHash = store(storage, "third-object");
		PackDocument third = document(FIRST_PACK, thirdHash, Files.size(storage.objectFile(thirdHash)), Instant.parse("2026-01-03T00:00:00Z"), second);
		TestPacks.stageGeneration(storage, first);
		TestPacks.stageGeneration(storage, second);
		TestPacks.stageGeneration(storage, third);
		Files.delete(storage.objectFile(second.policySha1()));

		PackDocument newest = new ClientGenerationStore(storage).newestDocument(FIRST_PACK);

		assertEquals(third.contentToken(), newest.contentToken());
		assertEquals(third.policySha1(), newest.policySha1());
	}

	@Test
	void newestDocumentSkipsAHeadMirrorWhoseTokensAreNotDigests() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "projection-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		Path headFile = storage.historyHeadFile(FIRST_PACK);
		Files.createDirectories(headFile.getParent());
		GenerationJsons.HeadDocumentFields fields = TestPacks.head(document.manifest());
		fields.contentToken = "not-a-sha1";
		Files.writeString(headFile, ConfigTools.GSON.toJson(fields), StandardCharsets.UTF_8);

		PackDocument newest = new ClientGenerationStore(storage).newestDocument(FIRST_PACK);

		assertEquals(document.contentToken(), newest.contentToken());
		assertEquals(document.policySha1(), newest.policySha1());
	}

	@Test
	void compactionReclaimsNeverConsentedFetchesWhole() throws Exception {
		ClientStorage storage = storage();
		String keptHash = store(storage, "kept-object");
		String fetchedHash = store(storage, "fetched-object");
		PackDocument kept = document(FIRST_PACK, keptHash, Files.size(storage.objectFile(keptHash)), TestPacks.CREATED);
		PackDocument fetched = document(SECOND_PACK, fetchedHash, Files.size(storage.objectFile(fetchedHash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, kept);
		TestPacks.stageGeneration(storage, fetched);
		storage.writeActiveState(kept);
		ClientGenerationStore generations = new ClientGenerationStore(storage);

		// The cancelled install is cache: the accepted pack's history and objects survive, the fetch's do not.
		ClientGenerationStore.CompactionResult result = generations.compact();

		assertEquals(List.of(FIRST_PACK), generations.installedPackIds());
		assertFalse(Files.exists(storage.historyPackDirectory(SECOND_PACK)));
		assertTrue(Files.exists(storage.objectFile(keptHash)));
		assertFalse(Files.exists(storage.objectFile(fetchedHash)));
		assertFalse(Files.exists(storage.objectFile(fetched.policySha1())));
		assertEquals(2, result.collection().deletedObjectCount());
	}

	@Test
	void decliningAnAdvanceDetachesUntilAnExplicitAttach() throws Exception {
		ClientStorage storage = storage();
		String activeHash = store(storage, "active-object");
		String newerHash = store(storage, "newer-object");
		PackDocument active = document(FIRST_PACK, activeHash, Files.size(storage.objectFile(activeHash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, active);
		storage.writeActiveState(active);
		ClientGenerationStore generations = new ClientGenerationStore(storage);

		generations.detachOnDeclinedAdvance(FIRST_PACK, active.contentToken());
		assertFalse(generations.isDetached(FIRST_PACK), "declining the already-active generation cannot detach");

		generations.detachOnDeclinedAdvance(FIRST_PACK, newerHash);
		assertTrue(generations.isDetached(FIRST_PACK));

		assertTrue(generations.headMatchesActive(FIRST_PACK, active.contentToken()));
		assertTrue(generations.isDetached(FIRST_PACK), "the head reaching the kept generation never dissolves detachment on its own");
		assertFalse(generations.headMatchesActive(FIRST_PACK, newerHash));

		storage.setDetached(FIRST_PACK, false);
		assertFalse(generations.isDetached(FIRST_PACK), "only an explicit attach ends detachment");
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
		storage.writeActiveState(first);
		generations.detachOnDeclinedAdvance(FIRST_PACK, secondHash);
		assertTrue(generations.isDetached(FIRST_PACK));

		storage.writeActiveState(first);
		assertTrue(generations.isDetached(FIRST_PACK), "committing the same pack preserves its sovereignty");

		generations.detachOnDeclinedAdvance(SECOND_PACK, firstHash);
		assertFalse(generations.isDetached(SECOND_PACK), "the flag belongs to the active pack only");

		storage.writeActiveState(second);
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
		storage.writeActiveState(first);
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
		assertTrue(GeneratedCopyState.loadAll(storage).stream().anyMatch(state -> state.contentToken().equals(third.contentToken()) && state.selectionDigest().equals(selectionDigest)),
				"kept generations keep their generated-copy state");
		assertTrue(GeneratedCopyState.loadAll(storage).stream().noneMatch(state -> state.contentToken().equals(second.contentToken())), "trimmed generations lose their generated-copy state");
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
	void compactionDoesNotReportNeverFetchedPoliciesAsMissing() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		String secondHash = store(storage, "second-object");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), TestPacks.CREATED);
		PackDocument second = document(FIRST_PACK, secondHash, Files.size(storage.objectFile(secondHash)), TestPacks.CREATED.plusSeconds(1), first);
		TestPacks.stageGeneration(storage, first);
		TestPacks.stageGeneration(storage, second);
		storage.writeActiveState(second);
		Files.delete(storage.objectFile(first.policySha1()));

		ClientGenerationStore.CompactionResult result = new ClientGenerationStore(storage).compact();

		assertEquals(0, result.collection().after().missingReferencedObjectCount());
		assertTrue(Files.exists(storage.objectFile(second.policySha1())));
		assertFalse(Files.exists(storage.objectFile(first.policySha1())));
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
		storage.writeActiveState(second);
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
		storage.writeActiveState(second);
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

	@Test
	void hostingServesTheActivePackFromMirrorAndCas() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "projection-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		writeHead(storage, FIRST_PACK, document);
		storage.writeActiveState(document);

		GenerationHosting hosting = new ClientGenerationStore(storage).hosting();

		assertEquals(storage.historyHeadFile(FIRST_PACK), hosting.get(GenerationHosting.HEAD_DOCUMENT_KEY));
		assertEquals(storage.historyJournalFile(FIRST_PACK), hosting.get(GenerationHosting.JOURNAL_KEY));
		assertEquals(storage.objectFile(document.policySha1()), hosting.get(document.policySha1()));
		assertEquals(storage.objectFile(hash), hosting.get(hash));
	}

	@Test
	void hostingFailsLoudlyWhenAContentObjectIsMissing() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "projection-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		writeHead(storage, FIRST_PACK, document);
		storage.writeActiveState(document);
		Files.delete(storage.objectFile(hash));

		assertThrows(IOException.class, () -> new ClientGenerationStore(storage).hosting());
	}

	@Test
	void hostingWithoutAnActivePackFails() throws Exception {
		ClientStorage storage = storage();
		assertThrows(IOException.class, () -> new ClientGenerationStore(storage).hosting());
	}

	private static void writeHead(ClientStorage storage, String modpackId, PackDocument document) throws IOException {
		GenerationJsons.HeadDocumentFields head = new GenerationJsons.HeadDocumentFields();
		head.contentToken = document.contentToken();
		head.policySha1 = document.policySha1();
		head.journalHead = 1;
		ConfigTools.writeAtomic(storage.historyHeadFile(modpackId), head);
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private static PackDocument document(String modpackId, String hash, long size, Instant createdAt) {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(size, false, hash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "", "General", GroupSelectionMode.REQUIRED, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("mods/test.jar", file)));
		GroupManifest manifest = new GroupManifest(modpackId, "Test", "", "", "", "", new TreeMap<>(Map.of("main", group)));
		return PackDocument.create(manifest, TestPacks.policySha1(manifest), createdAt, null);
	}

	private static PackDocument document(String modpackId, String hash, long size, Instant createdAt, PackDocument parent) {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(size, false, hash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "", "General", GroupSelectionMode.REQUIRED, new TreeSet<>(), new TreeSet<>(), Set.of(),
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
