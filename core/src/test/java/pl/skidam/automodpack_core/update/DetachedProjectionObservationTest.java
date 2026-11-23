package pl.skidam.automodpack_core.update;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * Red-team reproduction: a projection row is not always immutable pack content. A detached pack's files are
 * player-editable, and a same-size rewrite that restores the mtime is served from the named tripwire. The state
 * history then records the old hash for the new bytes, so the pre-apply checkpoint never sees the edit.
 */
class DetachedProjectionObservationTest {
	@TempDir
	Path temporaryDirectory;

	private static final FileTime ORIGINAL = FileTime.from(Instant.ofEpochSecond(1_700_000_000L));

	/** The supported editing mode: detach publishes the pack and drops the projection's read-only policy. */
	private static void detach(ClientStorage storage) throws Exception {
		byte[] bytes = "pack bytes".getBytes(UTF_8);
		String hash = UpdateTestFixtures.store(storage, bytes);
		var target = UpdateTestFixtures.target(storage, "mods/pack.jar", "mod", false, hash, bytes.length);
		storage.writeActiveState(target.document(), target.selection().intent(), true);
	}

	@Test
	void aSameSizeProjectionEditWithRestoredMtimeIsServedStale() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		detach(storage);
		Path file = storage.activePath("config/pack.toml");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "one", UTF_8);
		Files.setLastModifiedTime(file, ORIGINAL);
		String originalHash = HashUtils.getHash(file);

		InstanceTree.Key key = new InstanceTree.Key(Root.PROJECTION, "", LogicalPath.normalize("config/pack.toml"));
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			InstanceTree before = InstanceTree.observe(storage, Set.of(key), cache);
			assertEquals(originalHash, before.file(Root.PROJECTION, "", "config/pack.toml").sha1());

			// The player (or a deployer that preserves timestamps) replaces the bytes in place, same length, old mtime.
			Files.writeString(file, "two", UTF_8);
			Files.setLastModifiedTime(file, ORIGINAL);
			String editedHash = HashUtils.getHash(file);
			assertNotEquals(originalHash, editedHash);

			InstanceTree after = InstanceTree.observe(storage, Set.of(key), cache);

			// The real defect: the observation answers with the pre-edit hash.
			assertEquals(editedHash, after.file(Root.PROJECTION, "", "config/pack.toml").sha1(),
					"a projection row rewritten in place with a preserved mtime must be re-hashed");
		}
	}

	@Test
	void thePreApplyCheckpointMissesThatEdit() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		detach(storage);
		Path file = storage.activePath("config/pack.toml");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "one", UTF_8);
		Files.setLastModifiedTime(file, ORIGINAL);

		InstanceTree.Key key = new InstanceTree.Key(Root.PROJECTION, "", LogicalPath.normalize("config/pack.toml"));
		StateHistory.snapshotIfDirty(storage, Set.of(key), ClientStateJournal.Kind.LIVE, "abc1234", "before-edit");
		int recorded = ClientStateJournal.open(storage).entries().size();
		assertEquals(1, recorded);

		// A detached pack's projection file is the player's file; this edit is real content, not pack bytes.
		Files.writeString(file, "two", UTF_8);
		Files.setLastModifiedTime(file, ORIGINAL);

		StateHistory.snapshotIfDirty(storage, Set.of(key), ClientStateJournal.Kind.LIVE, "abc1234", "before-apply");

		assertEquals(recorded + 1, ClientStateJournal.open(storage).entries().size(),
				"the pre-apply checkpoint must record the edited projection row before the next apply overwrites it");
	}

	/** The supported editing mode is detach, but an attached pack can still hold a writable private copy; it is the player's. */
	@Test
	void anAttachedPacksUnsealedPrivateCopyIsStillPlayerContent() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		byte[] bytes = "pack bytes".getBytes(UTF_8);
		String hash = UpdateTestFixtures.store(storage, bytes);
		var target = UpdateTestFixtures.target(storage, "mods/pack.jar", "mod", false, hash, bytes.length);
		storage.writeActiveState(target.document(), target.selection().intent());
		Path file = storage.activePath("config/pack.toml");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "one", UTF_8);
		Files.setLastModifiedTime(file, ORIGINAL);
		String originalHash = HashUtils.getHash(file);

		InstanceTree.Key key = new InstanceTree.Key(Root.PROJECTION, "", LogicalPath.normalize("config/pack.toml"));
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			assertEquals(originalHash, InstanceTree.observe(storage, Set.of(key), cache).file(Root.PROJECTION, "", "config/pack.toml").sha1());

			Files.writeString(file, "two", UTF_8);
			Files.setLastModifiedTime(file, ORIGINAL);

			assertEquals(HashUtils.getHash(file), InstanceTree.observe(storage, Set.of(key), cache).file(Root.PROJECTION, "", "config/pack.toml").sha1(),
					"an attached pack's writable copy is not the sealed CAS inode, so a restored mtime must not answer for it");
		}
	}

	@Test
	void aSameSizeGameFileEditWithRestoredMtimeIsRehashed() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		Path file = storage.gamePath("config/server.toml");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "one", UTF_8);
		Files.setLastModifiedTime(file, ORIGINAL);
		String originalHash = HashUtils.getHash(file);

		InstanceTree.Key key = new InstanceTree.Key(Root.GAME_DIR, "", LogicalPath.normalize("config/server.toml"));
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			InstanceTree before = InstanceTree.observe(storage, Set.of(key), cache);
			assertEquals(originalHash, before.file(Root.GAME_DIR, "", "config/server.toml").sha1());

			Files.writeString(file, "two", UTF_8);
			Files.setLastModifiedTime(file, ORIGINAL);
			String editedHash = HashUtils.getHash(file);
			assertNotEquals(originalHash, editedHash);

			InstanceTree after = InstanceTree.observe(storage, Set.of(key), cache);

			assertEquals(editedHash, after.file(Root.GAME_DIR, "", "config/server.toml").sha1(),
					"a game-directory row is player-editable too; a same-tick rewrite with a preserved mtime must be re-hashed");
		}
	}
}
