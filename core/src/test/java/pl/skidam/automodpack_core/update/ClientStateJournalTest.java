package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStateJournal.Kind;
import pl.skidam.automodpack_core.update.ClientStateJournal.Snapshot;
import pl.skidam.automodpack_core.update.InstanceTree.Key;
import pl.skidam.automodpack_core.update.InstanceTree.LiveIdentity;
import pl.skidam.automodpack_core.update.InstanceTree.TrackedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;

class ClientStateJournalTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void snapshotsRoundTripAndShareIdenticalTrees() throws Exception {
		ClientStorage storage = storage();
		InstanceTree tree = InstanceTree.of(LiveIdentity.empty(), List.of(new TrackedFile(Root.GAME_DIR, "", "mods/a.jar", hash("a"), 1)));
		tree.write(storage);
		ClientStateJournal journal = ClientStateJournal.open(storage);
		journal.append(tree.sha1(), Kind.LIVE, "", "txn-1");
		journal.append(tree.sha1(), Kind.INSTALL, "abc1234", "txn-2");
		assertThrows(Exception.class, () -> journal.append(new Snapshot(2, 1, tree.sha1(), Kind.UPDATE, "abc1234", "txn-3", Instant.parse("2026-09-18T00:00:00Z"))));

		List<Snapshot> entries = ClientStateJournal.open(storage).entries();
		assertEquals(2, entries.size());
		assertEquals(Kind.LIVE, entries.get(0).kind());
		assertEquals(tree.sha1(), entries.get(1).treeSha1());
		assertEquals(InstanceTree.read(storage, entries.get(0).treeSha1()).sha1(), tree.sha1());
	}

	@Test
	void dirtySnapshotThenAfterAndForgetPrefix() throws Exception {
		ClientStorage storage = storage();
		Files.createDirectories(storage.modsDirectory());
		byte[] bytes = "player-mod".getBytes(StandardCharsets.UTF_8);
		Files.write(storage.gamePath("mods/player.jar"), bytes);
		String hash = HashUtils.sha1(bytes);
		ClientObjectStore.storeObject(storage, hash, bytes);

		StateHistory.snapshotIfDirty(storage, Set.of(new Key(Root.GAME_DIR, "", "mods/player.jar")), Kind.LIVE, "", "before");
		assertEquals(1, StateHistory.entries(storage).size());
		StateHistory.snapshotIfDirty(storage, Set.of(new Key(Root.GAME_DIR, "", "mods/player.jar")), Kind.LIVE, "", "before");
		assertEquals(1, StateHistory.entries(storage).size(), "Identical live is not a second row");

		Files.write(storage.gamePath("mods/player.jar"), "changed".getBytes(StandardCharsets.UTF_8));
		byte[] changed = "changed".getBytes(StandardCharsets.UTF_8);
		String changedHash = HashUtils.sha1(changed);
		ClientObjectStore.storeObject(storage, changedHash, changed);
		StateHistory.snapshotIfDirty(storage, Set.of(new Key(Root.GAME_DIR, "", "mods/player.jar")), Kind.UPDATE, "abc1234", "after");
		assertEquals(2, StateHistory.entries(storage).size());

		StateHistory.forgetOlderThan(storage, StateHistory.entries(storage).get(1).seq());
		assertEquals(1, StateHistory.entries(storage).size());
		assertEquals(Kind.UPDATE, StateHistory.entries(storage).get(0).kind());
	}

	@Test
	void fileRestoreRefusesOwnedAndOverwrite() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "player-mod".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(bytes);
		ClientObjectStore.storeObject(storage, hash, bytes);
		InstanceTree tree = InstanceTree.of(LiveIdentity.empty(), List.of(new TrackedFile(Root.GAME_DIR, "", "oldmods/player.jar", hash, bytes.length)));
		tree.write(storage);
		ClientStateJournal.open(storage).append(tree.sha1(), Kind.UPDATE, "abc1234", "txn-1");

		assertThrows(Exception.class, () -> StateHistory.restoreFile(storage, 1, Root.PROJECTION, "mods/player.jar"));
		PackDocument document = TestPacks.document(TestPacks.manifest("pack", "config/pack.txt", "pack-file"));
		TestPacks.stageGeneration(storage, document);
		storage.writeActiveState(document, new SelectionIntent(Set.of("main")));
		storage.writeSelectedModpackId(document.manifest().modpackId());
		Path restored = StateHistory.restoreFile(storage, 1, Root.GAME_DIR, "oldmods/player.jar");
		assertEquals("player-mod", Files.readString(restored, StandardCharsets.UTF_8));
		assertEquals(Kind.FILE_RESTORE, StateHistory.entries(storage).get(StateHistory.entries(storage).size() - 1).kind());
		assertFalse(storage.readActiveState().detached);

		Files.writeString(restored, "changed by hand", StandardCharsets.UTF_8);
		assertThrows(Exception.class, () -> StateHistory.restoreFile(storage, 1, Root.GAME_DIR, "oldmods/player.jar"));
		Path copy = StateHistory.saveFileCopy(storage, 1, Root.GAME_DIR, "", "oldmods/player.jar");
		assertEquals("player-mod", Files.readString(copy, StandardCharsets.UTF_8));
	}

	@Test
	void theRunningModJarIsNeverTrackedOrRestoredOver() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "automodpack-jar".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(bytes);
		Path runningJar = storage.gamePath("mods/automodpack-9.9.9.jar");
		Files.createDirectories(runningJar.getParent());
		Files.write(runningJar, bytes);
		Path previousThisModJar = Constants.THIS_MOD_JAR;
		Constants.THIS_MOD_JAR = runningJar;
		try {
			Set<Key> extra = Set.of(new Key(Root.GAME_DIR, "", "mods/automodpack-9.9.9.jar"));
			StateHistory.snapshotIfDirty(storage, extra, Kind.LIVE, "", "txn-1");
			InstanceTree head = InstanceTree.read(storage, ClientStateJournal.open(storage).head().treeSha1());
			assertEquals(List.of(), head.files());
			assertEquals(StateHistory.FileGate.PROTECTED, StateHistory.fileRestoreGate(storage, Root.GAME_DIR, "mods/automodpack-9.9.9.jar"));
			assertThrows(Exception.class, () -> StateHistory.restoreFile(storage, 1, Root.GAME_DIR, "mods/automodpack-9.9.9.jar"));
			assertEquals("automodpack-jar", Files.readString(runningJar, StandardCharsets.UTF_8));
		} finally {
			Constants.THIS_MOD_JAR = previousThisModJar;
		}
	}

	@Test
	void checkoutRestoresFilesWhenPackHistoryIsGone() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "restored".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(bytes);
		ClientObjectStore.storeObject(storage, hash, bytes);
		String token = hash("token");
		InstanceTree tree = InstanceTree.of(new LiveIdentity("abc1234", token, false, null, List.of()),
				List.of(new TrackedFile(Root.GAME_DIR, "", "config/pack.txt", hash, bytes.length)));
		tree.write(storage);
		ClientStateJournal.open(storage).append(tree.sha1(), Kind.INSTALL, "abc1234", "txn-1");
		Path live = storage.gamePath("config/pack.txt");
		Files.createDirectories(live.getParent());
		Files.writeString(live, "current", StandardCharsets.UTF_8);

		assertEquals(StateHistory.Restorability.READY, StateHistory.views(storage).get(0).restorability());
		StateHistory.checkout(storage, 1);
		assertEquals("restored", Files.readString(live, StandardCharsets.UTF_8));
		assertNull(storage.readActiveState());
	}

	@Test
	void removedPackCanBeRestoredUntilItsTimelineCheckpointIsForgotten() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "restored pack file".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(bytes);
		ClientObjectStore.storeObject(storage, hash, bytes);
		PackDocument document = TestPacks.document(TestPacks.manifest("pack", "config/pack.txt", "restored pack file"));
		TestPacks.stageGeneration(storage, document);
		SelectionIntent selection = new SelectionIntent(Set.of("main"));
		storage.writeActiveState(document, selection);
		new ClientSelectionStore(storage.selectionFile()).put(document.manifest().modpackId(), selection);
		InstanceTree installed = InstanceTree.of(new LiveIdentity(document.manifest().modpackId(), document.contentToken(), false, selection, List.of()),
				List.of(new TrackedFile(Root.PROJECTION, "", "config/pack.txt", hash, bytes.length)));
		installed.write(storage);
		ClientStateJournal journal = ClientStateJournal.open(storage);
		journal.append(installed.sha1(), Kind.INSTALL, document.manifest().modpackId(), "install");
		Path projected = storage.activePath("config/pack.txt");
		Files.createDirectories(projected.getParent());
		Files.write(projected, bytes);

		storage.clearActiveState();
		new ClientSelectionStore(storage.selectionFile()).remove(document.manifest().modpackId());
		Files.delete(projected);
		InstanceTree removed = InstanceTree.of(LiveIdentity.empty(), List.of());
		removed.write(storage);
		journal.append(removed.sha1(), Kind.REMOVAL, document.manifest().modpackId(), "remove");
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.forgetModpack(document.manifest().modpackId());

		assertTrue(Files.exists(storage.historyJournalFile(document.manifest().modpackId())));
		assertTrue(generations.installedPackIds().isEmpty());
		generations.compact();
		assertTrue(Files.exists(storage.objectFile(document.policySha1())));
		assertTrue(Files.exists(storage.objectFile(hash)));
		assertEquals(StateHistory.Restorability.READY, StateHistory.views(storage).get(0).restorability());
		StateHistory.checkout(storage, 1);
		assertEquals("restored pack file", Files.readString(projected, StandardCharsets.UTF_8));
		assertNull(storage.readActiveState());

		storage.clearActiveState();
		new ClientSelectionStore(storage.selectionFile()).remove(document.manifest().modpackId());
		Files.delete(projected);
		journal = ClientStateJournal.open(storage);
		journal.append(removed.sha1(), Kind.REMOVAL, document.manifest().modpackId(), "remove-again");
		generations.forgetModpack(document.manifest().modpackId());
		StateHistory.forgetOlderThan(storage, ClientStateJournal.open(storage).head().seq());

		assertFalse(Files.exists(storage.historyJournalFile(document.manifest().modpackId())));
		assertFalse(Files.exists(storage.objectFile(hash)));
		assertFalse(Files.exists(storage.objectFile(document.policySha1())));
	}

	@Test
	void checkoutRestoresDetachedSelectionAndReportsRestartWork() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "mod".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(bytes);
		ClientObjectStore.storeObject(storage, hash, bytes);
		GroupManifest.GroupFile mod = new GroupManifest.GroupFile(bytes.length, false, hash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "test pack", "General", GroupSelectionMode.OPTIONAL, Set.of(), Set.of(), Set.of(), Map.of("mods/test.jar", mod));
		GroupManifest manifest = new GroupManifest("abc1234", "test pack", "", "", "", "", Map.of("main", group));
		PackDocument document = PackDocument.create(manifest, TestPacks.policySha1(manifest), TestPacks.CREATED, null);
		TestPacks.stageGeneration(storage, document);
		SelectionIntent selection = new SelectionIntent(Set.of("main"));
		InstanceTree target = InstanceTree.of(new LiveIdentity("abc1234", document.contentToken(), false, selection, List.of()),
				List.of(new TrackedFile(Root.PROJECTION, "", "mods/test.jar", hash, bytes.length)));
		target.write(storage);
		ClientStateJournal.open(storage).append(target.sha1(), Kind.INSTALL, "abc1234", "install");

		StateHistory.CheckoutResult result = StateHistory.checkout(storage, 1);

		assertEquals("", storage.selectedModpackId());
		assertNull(storage.readActiveState());
		assertEquals("mod", Files.readString(storage.activePath("mods/test.jar"), StandardCharsets.UTF_8));
		assertEquals(RestartDemand.REQUIRED, result.restartDemand());
		assertEquals(1, result.changes().changes().size());
		assertEquals(Kind.RESTORE, ClientStateJournal.open(storage).head().kind());
	}

	@Test
	void checkoutClearsThePointerForAPreInstallSnapshotAndKeepsTheClaim() throws Exception {
		ClientStorage storage = storage();
		byte[] currentBytes = "current".getBytes(StandardCharsets.UTF_8);
		String currentHash = HashUtils.sha1(currentBytes);
		ClientObjectStore.storeObject(storage, currentHash, currentBytes);
		GroupManifest.GroupFile mod = new GroupManifest.GroupFile(currentBytes.length, false, currentHash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "test pack", "General", GroupSelectionMode.OPTIONAL, Set.of(), Set.of(), Set.of(), Map.of("mods/test.jar", mod));
		GroupManifest manifest = new GroupManifest("abc1234", "test pack", "", "", "", "", Map.of("main", group));
		PackDocument document = PackDocument.create(manifest, TestPacks.policySha1(manifest), TestPacks.CREATED, null);
		TestPacks.stageGeneration(storage, document);
		SelectionIntent selection = new SelectionIntent(Set.of("main"));
		storage.writeActiveState(document, selection);
		storage.writeSelectedModpackId("abc1234");
		new ClientSelectionStore(storage.selectionFile()).put("abc1234", selection);
		Path projected = storage.activePath("mods/test.jar");
		Files.createDirectories(projected.getParent());
		Files.write(projected, currentBytes);
		InstanceTree empty = InstanceTree.of(LiveIdentity.empty(), List.of());
		empty.write(storage);
		ClientStateJournal journal = ClientStateJournal.open(storage);
		journal.append(empty.sha1(), Kind.REMOVAL, "abc1234", "before-pack");
		InstanceTree installed = InstanceTree.of(new LiveIdentity("abc1234", document.contentToken(), false, selection, List.of()),
				List.of(new TrackedFile(Root.PROJECTION, "", "mods/test.jar", currentHash, currentBytes.length)));
		installed.write(storage);
		journal.append(installed.sha1(), Kind.INSTALL, "abc1234", "install");

		StateHistory.CheckoutResult result = StateHistory.checkout(storage, 1);

		// A pre-install tree ran no pack, so the pointer must not claim one; the claim itself is consent and survives.
		assertEquals("abc1234", storage.selectedModpackId());
		assertNull(storage.readActiveState());
		assertEquals(selection, new ClientSelectionStore(storage.selectionFile()).get("abc1234").orElseThrow());
		assertFalse(Files.exists(projected));
		assertEquals(RestartDemand.REQUIRED, result.restartDemand());
		assertEquals(Kind.RESTORE, ClientStateJournal.open(storage).head().kind());
		assertEquals(StateHistory.Restorability.CURRENT, StateHistory.views(storage).get(0).restorability());
	}

	@Test
	void checkoutDetachesWithoutOpeningTheCasObject() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "mod".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(bytes);
		ClientObjectStore.storeObject(storage, hash, bytes);
		Path object = storage.objectFile(hash);
		GroupManifest.GroupFile mod = new GroupManifest.GroupFile(bytes.length, false, hash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "test pack", "General", GroupSelectionMode.OPTIONAL, Set.of(), Set.of(), Set.of(), Map.of("mods/test.jar", mod));
		GroupManifest manifest = new GroupManifest("abc1234", "test pack", "", "", "", "", Map.of("main", group));
		PackDocument document = PackDocument.create(manifest, TestPacks.policySha1(manifest), TestPacks.CREATED, null);
		TestPacks.stageGeneration(storage, document);
		SelectionIntent selection = new SelectionIntent(Set.of("main"));
		storage.writeActiveState(document, selection);
		storage.writeSelectedModpackId("abc1234");
		Path projected = storage.activePath("mods/test.jar");
		Files.createDirectories(projected.getParent());
		VerifiedFileTransfer.linkAtomic(object, projected, bytes.length, hash, null);
		InstanceTree installed = InstanceTree.of(new LiveIdentity("abc1234", document.contentToken(), false, selection, List.of()),
				List.of(new TrackedFile(Root.PROJECTION, "", "mods/test.jar", hash, bytes.length)));
		installed.write(storage);
		ClientStateJournal.open(storage).append(installed.sha1(), Kind.INSTALL, "abc1234", "install");
		assertTrue(ImmutableFiles.isProtected(object));

		StateHistory.checkout(storage, 1);

		assertTrue(storage.readActiveState().detached);
		assertTrue(ImmutableFiles.isProtected(object));
		assertFalse(ImmutableFiles.isProtected(projected));
		Files.writeString(projected, "edited", StandardCharsets.UTF_8);
		assertEquals("mod", Files.readString(object, StandardCharsets.UTF_8));
	}

	@Test
	void checkoutPointsAtThePackTheSnapshotRanAndKeepsBothClaims() throws Exception {
		ClientStorage storage = storage();
		byte[] aBytes = "a".getBytes(StandardCharsets.UTF_8);
		byte[] bBytes = "b".getBytes(StandardCharsets.UTF_8);
		String aHash = HashUtils.sha1(aBytes);
		String bHash = HashUtils.sha1(bBytes);
		ClientObjectStore.storeObject(storage, aHash, aBytes);
		ClientObjectStore.storeObject(storage, bHash, bBytes);
		GroupManifest aManifest = TestPacks.manifest("pack a", "mods/a.jar", "a");
		PackDocument aDocument = TestPacks.document(aManifest);
		TestPacks.stageGeneration(storage, aDocument);
		GroupManifest.GroupFile bMod = new GroupManifest.GroupFile(bBytes.length, false, bHash, null);
		GroupManifest.Group bGroup = new GroupManifest.Group("", "pack b", "General", GroupSelectionMode.OPTIONAL, Set.of(), Set.of(), Set.of(), Map.of("mods/b.jar", bMod));
		GroupManifest bManifest = new GroupManifest("def5678", "pack b", "", "", "", "", Map.of("main", bGroup));
		PackDocument bDocument = TestPacks.document(bManifest);
		TestPacks.stageGeneration(storage, bDocument);
		SelectionIntent aSelection = new SelectionIntent(Set.of("main"));
		SelectionIntent bSelection = new SelectionIntent(Set.of("main"));
		new ClientSelectionStore(storage.selectionFile()).put("abc1234", aSelection);
		new ClientSelectionStore(storage.selectionFile()).put("def5678", bSelection);
		storage.writeActiveState(bDocument, bSelection);
		storage.writeSelectedModpackId("def5678");
		InstanceTree aTree = InstanceTree.of(new LiveIdentity("abc1234", aDocument.contentToken(), false, aSelection, List.of()),
				List.of(new TrackedFile(Root.PROJECTION, "", "mods/a.jar", aHash, aBytes.length)));
		InstanceTree bTree = InstanceTree.of(new LiveIdentity("def5678", bDocument.contentToken(), false, bSelection, List.of()),
				List.of(new TrackedFile(Root.PROJECTION, "", "mods/b.jar", bHash, bBytes.length)));
		aTree.write(storage);
		bTree.write(storage);
		ClientStateJournal journal = ClientStateJournal.open(storage);
		journal.append(bTree.sha1(), Kind.INSTALL, "def5678", "install-b");
		journal.append(aTree.sha1(), Kind.INSTALL, "abc1234", "install-a");

		StateHistory.checkout(storage, 2);

		assertEquals("abc1234", storage.readActiveState().modpackId);
		assertEquals(aDocument.contentToken(), storage.readActiveState().contentToken);
		assertTrue(storage.readActiveState().detached);
		assertEquals("def5678", storage.selectedModpackId());
		assertEquals(aSelection, new ClientSelectionStore(storage.selectionFile()).get("abc1234").orElseThrow());
		assertEquals(bSelection, new ClientSelectionStore(storage.selectionFile()).get("def5678").orElseThrow());
		assertEquals("a", Files.readString(storage.activePath("mods/a.jar"), StandardCharsets.UTF_8));
		assertEquals("abc1234", ClientStateJournal.open(storage).head().modpackId());
	}

	@Test
	void checkoutPointsAtTheOlderGenerationTheSnapshotRan() throws Exception {
		ClientStorage storage = storage();
		byte[] one = "one".getBytes(StandardCharsets.UTF_8);
		byte[] two = "two".getBytes(StandardCharsets.UTF_8);
		String oneHash = HashUtils.sha1(one);
		String twoHash = HashUtils.sha1(two);
		ClientObjectStore.storeObject(storage, oneHash, one);
		ClientObjectStore.storeObject(storage, twoHash, two);
		GroupManifest v1Manifest = TestPacks.manifest("generation one", "mods/test.jar", "one");
		PackDocument v1 = TestPacks.document(v1Manifest);
		GroupManifest.GroupFile v2Mod = new GroupManifest.GroupFile(two.length, false, twoHash, null);
		GroupManifest.Group v2Group = new GroupManifest.Group("", "generation two", "General", GroupSelectionMode.OPTIONAL, Set.of(), Set.of(), Set.of(), Map.of("mods/test.jar", v2Mod));
		GroupManifest v2Manifest = new GroupManifest("abc1234", "generation two", "", "", "", "", Map.of("main", v2Group));
		PackDocument v2 = TestPacks.document(v2Manifest);
		TestPacks.stageGeneration(storage, v1);
		TestPacks.stageGeneration(storage, v2);
		SelectionIntent selection = new SelectionIntent(Set.of("main"));
		new ClientSelectionStore(storage.selectionFile()).put("abc1234", selection);
		storage.writeActiveState(v2, selection);
		storage.writeSelectedModpackId("abc1234");
		Path projected = storage.activePath("mods/test.jar");
		Files.createDirectories(projected.getParent());
		Files.write(projected, two);
		InstanceTree v1Tree = InstanceTree.of(new LiveIdentity("abc1234", v1.contentToken(), false, selection, List.of()),
				List.of(new TrackedFile(Root.PROJECTION, "", "mods/test.jar", oneHash, one.length)));
		InstanceTree v2Tree = InstanceTree.of(new LiveIdentity("abc1234", v2.contentToken(), false, selection, List.of()),
				List.of(new TrackedFile(Root.PROJECTION, "", "mods/test.jar", twoHash, two.length)));
		v1Tree.write(storage);
		v2Tree.write(storage);
		ClientStateJournal journal = ClientStateJournal.open(storage);
		journal.append(v1Tree.sha1(), Kind.INSTALL, "abc1234", "install");
		journal.append(v2Tree.sha1(), Kind.UPDATE, "abc1234", "update");

		StateHistory.checkout(storage, 1);

		assertEquals("abc1234", storage.readActiveState().modpackId);
		assertEquals(v1.contentToken(), storage.readActiveState().contentToken);
		assertTrue(storage.readActiveState().detached);
		assertEquals("one", Files.readString(projected, StandardCharsets.UTF_8));
	}

	@Test
	void checkoutLeavesFollowAloneWhenFilesAlreadyMatchAndNothingIsLoaded() throws Exception {
		ClientStorage storage = storage();
		InstanceTree target = InstanceTree.of(LiveIdentity.empty(), List.of());
		target.write(storage);
		ClientStateJournal.open(storage).append(target.sha1(), Kind.DEACTIVATION, "abc1234", "deactivate");
		storage.writeSelectedModpackId("abc1234");

		StateHistory.CheckoutResult result = StateHistory.checkout(storage, 1);

		assertEquals("abc1234", storage.selectedModpackId());
		assertNull(storage.readActiveState());
		assertEquals(RestartDemand.NONE, result.restartDemand());
		assertTrue(result.changes().changes().isEmpty());
		assertEquals(Kind.DEACTIVATION, ClientStateJournal.open(storage).head().kind());
	}

	@Test
	void checkoutRefusesWhileAnUpdateTransactionIsPending() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "restored".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(bytes);
		ClientObjectStore.storeObject(storage, hash, bytes);
		InstanceTree tree = InstanceTree.of(LiveIdentity.empty(), List.of(new TrackedFile(Root.GAME_DIR, "", "config/pack.txt", hash, bytes.length)));
		tree.write(storage);
		ClientStateJournal.open(storage).append(tree.sha1(), Kind.LIVE, "", "txn-1");
		Files.createDirectories(storage.transactionFile().getParent());
		Files.writeString(storage.transactionFile(), "{}", StandardCharsets.UTF_8);

		assertEquals(StateHistory.Restorability.UPDATE_PENDING, StateHistory.views(storage).get(0).restorability());
		IOException error = assertThrows(IOException.class, () -> StateHistory.checkout(storage, 1));
		assertTrue(error.getMessage().contains("pending"));
		error = assertThrows(IOException.class, () -> StateHistory.forgetOlderThan(storage, 1));
		assertTrue(error.getMessage().contains("pending"));
	}

	@Test
	void timelinePinsReachTheReferenceSweep() throws Exception {
		ClientStorage storage = storage();
		String installed = store(storage, "installed-bytes");
		InstanceTree tree = InstanceTree.of(LiveIdentity.empty(), List.of(new TrackedFile(Root.PROJECTION, "", "mods/installed.jar", installed, "installed-bytes".length())));
		tree.write(storage);
		ClientStateJournal.open(storage).append(tree.sha1(), Kind.INSTALL, "abc1234", "txn-1");
		assertTrue(ClientObjectStore.referencedHashes(storage).contains(installed));
	}

	private ClientStorage storage() throws Exception {
		return TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
	}

	private static String store(ClientStorage storage, String text) throws Exception {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(bytes);
		ClientObjectStore.storeObject(storage, hash, bytes);
		return hash;
	}

	private static String hash(String text) {
		return HashUtils.sha1(text.getBytes(StandardCharsets.UTF_8));
	}
}
