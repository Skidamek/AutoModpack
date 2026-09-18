package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStateJournal.Kind;
import pl.skidam.automodpack_core.update.ClientStateJournal.Snapshot;
import pl.skidam.automodpack_core.update.InstanceTree.LiveIdentity;
import pl.skidam.automodpack_core.update.InstanceTree.TrackedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;

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

		StateHistory.snapshotIfDirty(storage, Set.of(new UpdatePlan.FileKey(Root.GAME_DIR, "mods/player.jar")), Kind.LIVE, "", "before");
		assertEquals(1, StateHistory.entries(storage).size());
		StateHistory.recordAfter(storage, Set.of(new UpdatePlan.FileKey(Root.GAME_DIR, "mods/player.jar")), Kind.LIVE, "", "before");
		assertEquals(1, StateHistory.entries(storage).size(), "Identical live is not a second row");

		Files.write(storage.gamePath("mods/player.jar"), "changed".getBytes(StandardCharsets.UTF_8));
		byte[] changed = "changed".getBytes(StandardCharsets.UTF_8);
		String changedHash = HashUtils.sha1(changed);
		ClientObjectStore.storeObject(storage, changedHash, changed);
		StateHistory.recordAfter(storage, Set.of(new UpdatePlan.FileKey(Root.GAME_DIR, "mods/player.jar")), Kind.UPDATE, "abc1234", "after");
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
		Path restored = StateHistory.restoreFile(storage, 1, Root.GAME_DIR, "oldmods/player.jar");
		assertEquals("player-mod", Files.readString(restored, StandardCharsets.UTF_8));
		assertEquals(Kind.FILE_RESTORE, StateHistory.entries(storage).get(StateHistory.entries(storage).size() - 1).kind());

		Files.writeString(restored, "changed by hand", StandardCharsets.UTF_8);
		assertThrows(Exception.class, () -> StateHistory.restoreFile(storage, 1, Root.GAME_DIR, "oldmods/player.jar"));
		Path copy = StateHistory.saveFileCopy(storage, 1, Root.GAME_DIR, "oldmods/player.jar");
		assertEquals("player-mod", Files.readString(copy, StandardCharsets.UTF_8));
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
