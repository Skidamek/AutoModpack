package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.StorageJsons;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The review findings on the in-place projection, each asserting the fixed behaviour: repair never destroys on doubt,
 * every fallback names bytes the store holds, the Kilt gate decides once and relaunches once, and storage conditions
 * are failures. Runs on a case-insensitive filesystem too: set {@value UpdateTestFixtures#GAME_ROOT_ENVIRONMENT}.
 */
class ProjectionRepairTest {
	@TempDir
	Path temporaryDirectory;

	@AfterEach
	void forgetScan() {
		ClientStorage.forgetDirectoryScan();
	}

	private static final Map<String, String> OLD = Map.of("mods/alpha-1.0.jar", "alpha one", "mods/beta.jar", "beta", "config/alpha.toml", "a = 1");
	private static final Map<String, String> NEW = Map.of("mods/alpha-1.1.jar", "alpha two", "mods/beta.jar", "beta", "config/alpha.toml", "a = 2");

	/** F1: a hinted row the file's own cache record vouches for still names an object; a missing one is acquired before the mark. */
	@Test
	void aHintedFallbackRowWhoseObjectIsGoneIsAcquiredFirst() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			String hash = replaceWithARecordedCopyAndDropTheObject(storage, cache, "mods/alpha-1.0.jar", "alpha one");

			converge(storage, cache, NEW, Map.of("mods/alpha-1.0.jar", hash), ProjectionConvergence.Writes.NONE);
			assertTrue(ProjectionState.read(storage).fallback().stream().anyMatch(row -> row.sha1().equals(hash)));
			assertTrue(FileIntegrity.matches(storage.objectFile(hash), "alpha one".length(), hash), "the fallback's object exists before the first write");

			assertTrue(new ProjectionConvergence(storage, cache, ProjectionConvergence.Writes.NONE).restoreFallback());
			assertEquals(OLD, tree(storage));
		}
	}

	/** F1: the same without the hint, as before. */
	@Test
	void anUnhintedFallbackAcquiresTheBytes() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			replaceWithARecordedCopyAndDropTheObject(storage, cache, "mods/alpha-1.0.jar", "alpha one");
			converge(storage, cache, NEW, Map.of(), ProjectionConvergence.Writes.NONE);
			assertTrue(new ProjectionConvergence(storage, cache, ProjectionConvergence.Writes.NONE).restoreFallback());
			assertEquals(OLD, tree(storage));
		}
	}

	/** F1: a fallback the store can no longer complete falls back to the committed generation, keeping what the store cannot give back. */
	@Test
	void aFallbackTheStoreCannotCompleteRestoresTheCommittedGeneration() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		commit(storage);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			Path held = storage.activePath("config/alpha.toml");
			assertFalse(converge(storage, cache, NEW, Map.of(), holding(held::equals)).converged());
			dropObject(storage, rows(storage, Map.of("mods/alpha-1.0.jar", "alpha one")).get(0).sha1());
			Files.writeString(storage.activePath("mods/players.jar"), "player added", StandardCharsets.UTF_8);

			assertTrue(new ProjectionConvergence(storage, cache, ProjectionConvergence.Writes.NONE).restoreFallback());
			assertEquals(Map.of("mods/committed.jar", "committed"), tree(storage));
			assertFalse(ProjectionState.read(storage).converging());
			assertEquals(Map.of("mods/players.jar", "player added"), repairBackups(storage), "the file no object holds is kept before the restore");
		}
	}

	/**
	 * F2: a physical read failure is no unreadable document. Gates refuse, writers propagate, the sweep defers, and
	 * nothing is set aside or rebuilt: once the file reads again, the recorded fallback restores, player file included.
	 */
	@Test
	void aPhysicalReadFailureIsNeverRepaired() throws Exception {
		Assumptions.assumeTrue(Files.getFileAttributeView(temporaryDirectory, PosixFileAttributeView.class) != null);
		Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		commit(storage);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, Map.of("mods/alpha-1.0.jar", "alpha one"));
			Files.writeString(storage.activePath("mods/dropped.jar"), "players own", StandardCharsets.UTF_8);
			Path held = storage.activePath("mods/alpha-1.0.jar");
			assertFalse(converge(storage, cache, NEW, Map.of(), holding(held::equals)).converged());
			assertTrue(ProjectionState.read(storage).fallback().stream().anyMatch(row -> row.path().equals("mods/dropped.jar")));
			Map<String, String> mixed = tree(storage);

			Path state = storage.projectionStateFile();
			Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("---------"));
			try {
				Assumptions.assumeFalse(Files.isReadable(state), "this filesystem ignores permissions");
				IOException physical = assertThrows(IOException.class, () -> ProjectionState.owed(storage));
				assertFalse(physical instanceof ProjectionState.UnreadableException);
				assertNotNull(ProjectionState.loadRefusal(storage.gameDirectory()));
				assertNull(ClientStorage.loadableProjectionModsDirectory(storage.gameDirectory()));
				assertTrue(ClientStorage.directoryScan().refusedByState());
				IOException writer = assertThrows(IOException.class, () -> new ProjectionConvergence(storage, cache, ProjectionConvergence.Writes.NONE).restoreFallback());
				assertFalse(writer instanceof ProjectionState.UnreadableException);
				PublicationSweep.sweepUnpinnedDirectories(storage);
				assertEquals(mixed, tree(storage), "nothing touched the projection on a read error");
				assertEquals(List.of(), asides(storage), "nothing was set aside on a read error");
			} finally {
				Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rw-r--r--"));
			}
			assertTrue(new ProjectionConvergence(storage, cache, ProjectionConvergence.Writes.NONE).restoreFallback());
			assertEquals(Map.of("mods/alpha-1.0.jar", "alpha one", "mods/dropped.jar", "players own"), tree(storage));
		}
	}

	/** P7: the one way a document of this version goes missing - a non-atomic replace - reads unreadable, never legacy. */
	@Test
	void aStateMissingBesideItsTemporaryReadsUnreadable() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			Path state = storage.projectionStateFile();
			Path temporary = state.resolveSibling("." + state.getFileName() + "." + UUID.randomUUID() + ".tmp");
			Files.move(state, temporary);

			assertThrows(ProjectionState.UnreadableException.class, () -> ProjectionState.read(storage));
			assertNotNull(ProjectionState.loadRefusal(storage.gameDirectory()));
			assertTrue(ProjectionState.owed(storage));

			assertTrue(PublicationSweep.restoreFallback(storage));
			assertEquals(OLD, tree(storage), "no journal: the projection is kept as it is");
			assertFalse(ProjectionState.owed(storage));
			assertFalse(Files.exists(temporary), "the temporary is evidence now, not the next document");
		}
	}

	/** F4: with no journal nothing can be converging, so an unreadable document is rewritten settled and active/ is left alone. */
	@Test
	void anUnreadableStateWithoutAJournalSettlesAndLeavesTheProjectionAlone() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		commit(storage);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, Map.of("mods/committed.jar", "committed"));
		}
		long before = ProjectionState.read(storage).revision();
		Files.writeString(storage.activePath("mods/players.jar"), "player added", StandardCharsets.UTF_8);
		Files.writeString(storage.projectionStateFile(), "{torn", StandardCharsets.UTF_8);
		assertTrue(ProjectionState.owed(storage));
		assertNotNull(ProjectionState.loadRefusal(storage.gameDirectory()));

		PublicationSweep.sweepUnpinnedDirectories(storage);

		assertEquals(Map.of("mods/committed.jar", "committed", "mods/players.jar", "player added"), tree(storage));
		assertFalse(ProjectionState.owed(storage));
		assertTrue(ProjectionState.read(storage).revision() > before, "the repair moved the revision");
		assertEquals(1, asides(storage).size(), "the unreadable document is kept as evidence");
	}

	/** F4: with a journal the update rolls forward over the projection; the committed generation is only the fallback. */
	@Test
	void anUnreadableStateWithAJournalRollsForward() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		commit(storage);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			Files.writeString(storage.transactionFile(), "{}", StandardCharsets.UTF_8);
			Files.writeString(storage.projectionStateFile(), "{torn", StandardCharsets.UTF_8);

			Path held = storage.activePath("config/alpha.toml");
			assertFalse(converge(storage, cache, NEW, Map.of(), holding(held::equals)).converged());
			assertEquals(ProjectionState.Fallback.COMMITTED, ProjectionState.read(storage).fallbackSource());
			assertTrue(converge(storage, cache, NEW, Map.of(), ProjectionConvergence.Writes.NONE).converged());
			ProjectionState.settled(storage, "tx");
			assertEquals(NEW, tree(storage));
			assertEquals(ProjectionState.digest(rows(storage, NEW)), ProjectionState.read(storage).contentDigest());
		}
	}

	/** F4: an update abandoned after the repair restores the committed generation, and keeps every file the store cannot give back first. */
	@Test
	void anAbandonedRepairRestoresTheCommittedGenerationKeepingUnknownFiles() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		commit(storage);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			Files.delete(storage.activePath("mods/beta.jar"));
			Files.writeString(storage.activePath("mods/beta.jar"), "player patched", StandardCharsets.UTF_8);
			Files.writeString(storage.transactionFile(), "{}", StandardCharsets.UTF_8);
			Files.writeString(storage.projectionStateFile(), "{torn", StandardCharsets.UTF_8);
			// The journal's roll-forward runs first and stays owed on a held path.
			Path held = storage.activePath("config/alpha.toml");
			assertFalse(converge(storage, cache, NEW, Map.of(), holding(held::equals)).converged());
			assertEquals(Map.of("mods/beta.jar", "player patched"), repairBackups(storage), "the roll-forward kept the edit no object holds");
			Files.delete(storage.transactionFile());

			PublicationSweep.sweepUnpinnedDirectories(storage);

			assertEquals(Map.of("mods/committed.jar", "committed"), tree(storage));
			assertFalse(ProjectionState.owed(storage));
			assertEquals(Map.of("mods/beta.jar", "player patched"), repairBackups(storage), "only the file no object holds is kept");
		}
	}

	/** F4: a detached pack's projection is the player's; no repair rebuilds it. */
	@Test
	void anUnreadableStateNeverRebuildsADetachedPack() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		commit(storage);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, Map.of("mods/committed.jar", "committed"));
		}
		String modpackId = storage.readActiveState().modpackId;
		new ClientGenerationStore(storage).declareDetached(modpackId);
		assertTrue(storage.isDetached(modpackId));
		Files.writeString(storage.activePath("mods/committed.jar"), "player patched", StandardCharsets.UTF_8);
		Files.writeString(storage.activePath("mods/players-own.jar"), "player added", StandardCharsets.UTF_8);
		Files.writeString(storage.transactionFile(), "{}", StandardCharsets.UTF_8);
		Files.writeString(storage.projectionStateFile(), "{torn", StandardCharsets.UTF_8);

		assertTrue(ProjectionState.owed(storage));
		assertTrue(PublicationSweep.restoreFallback(storage));

		assertEquals(Map.of("mods/committed.jar", "player patched", "mods/players-own.jar", "player added"), tree(storage));
		assertFalse(ProjectionState.owed(storage));
	}

	/** F3: what no wait fixes is a failure with its reason, never a held file; a sharing violation still is one. */
	@Test
	void storageConditionsAreFailuresNotHeldFiles() {
		String file = temporaryDirectory.resolve("a.jar").toString();
		for (String reason : List.of("No space left on device", "Disk quota exceeded", "Read-only file system", "Operation not supported", "Function not implemented", "Input/output error"))
			assertFalse(UpdateCommit.isLockFailure(new FileSystemException(file, null, reason), false), reason);
		for (String reason : List.of("There is not enough space on the disk", "The media is write protected", "The request is not supported"))
			assertFalse(UpdateCommit.isLockFailure(new FileSystemException(file, null, reason), true), reason);
		assertEquals("the disk is full", StorageConditions.condition(new IOException(new FileSystemException(file, null, "No space left on device"))));
		assertEquals("the filesystem is read-only", StorageConditions.condition(new FileSystemException(file, null, "Read-only file system")));
		assertTrue(UpdateCommit.isLockFailure(new FileSystemException(file, null, "The process cannot access the file because it is being used by another process"), true));
		assertTrue(UpdateCommit.isLockFailure(new FileSystemException(file, null, "Text file busy"), false));
	}

	/**
	 * F5: the scan-time gate decides from one read; a boot whose recovery settled what the gate refused relaunches once,
	 * a persisted guard bounds it, and a boot that matches what its gate decided clears the guard.
	 */
	@Test
	void aRefusedScanRelaunchesOnceWhenRecoverySettlesIt() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			Path held = storage.activePath("config/alpha.toml");
			assertFalse(converge(storage, cache, NEW, Map.of(), holding(held::equals)).converged());

			assertNull(ClientStorage.loadableProjectionModsDirectory(storage.gameDirectory()));
			ClientStorage.DirectoryScan refused = ClientStorage.directoryScan();
			assertTrue(refused.refusedByState());
			assertFalse(refused.exposed());
			assertFalse(ProjectionRelaunch.required(storage, refused), "still converging: a relaunch would load nothing more");

			assertTrue(PublicationSweep.restoreFallback(storage));
			assertTrue(ProjectionRelaunch.required(storage, refused));
			assertTrue(Files.exists(storage.projectionRelaunchFile()));
			assertFalse(ProjectionRelaunch.required(storage, refused), "the guard allows one relaunch in a row");

			assertNotNull(ClientStorage.loadableProjectionModsDirectory(storage.gameDirectory()));
			ClientStorage.DirectoryScan exposed = ClientStorage.directoryScan();
			assertTrue(exposed.exposed());
			assertFalse(ProjectionRelaunch.required(storage, exposed));
			assertFalse(Files.exists(storage.projectionRelaunchFile()), "a stable boot clears the guard");
		}
	}

	/** F5: what the scanner took is compared by content: a restore to the same tree needs no relaunch, a new tree does. */
	@Test
	void anExposedScanRelaunchesOnlyWhenTheContentMoved() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			assertNotNull(ClientStorage.loadableProjectionModsDirectory(storage.gameDirectory()));
			ClientStorage.DirectoryScan scan = ClientStorage.directoryScan();
			assertTrue(scan.exposed());

			Path held = storage.activePath("config/alpha.toml");
			assertFalse(converge(storage, cache, NEW, Map.of(), holding(held::equals)).converged());
			assertTrue(PublicationSweep.restoreFallback(storage));
			assertTrue(ProjectionState.read(storage).revision() > scan.revision());
			assertFalse(ProjectionRelaunch.required(storage, scan), "the same tree came back");

			settle(storage, cache, NEW);
			assertTrue(ProjectionRelaunch.required(storage, scan));
		}
	}

	/** F6: the parent of every directory a run creates is forced before the run returns. */
	@Test
	void everyCreatedDirectoryIsForcedInItsParent() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		Set<Path> forced = new HashSet<>();
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			boolean activeExisted = Files.exists(storage.activeDirectory());
			converge(storage, cache, Map.of("a/b/c/deep.jar", "deep"), Map.of(), new ProjectionConvergence.Writes() {
				@Override
				public void forced(Path directory) {
					forced.add(directory);
				}
			});
		}
		Path active = storage.activeDirectory();
		for (Path directory : List.of(active, active.resolve("a"), active.resolve("a/b"), active.resolve("a/b/c"))) assertTrue(forced.contains(directory), directory + " in " + forced);
	}

	/** F7: an object the fallback acquires is pinned in this instance's receipt before it exists, and after the mark. */
	@Test
	void fallbackAcquisitionsArePinnedBeforeTheyExist() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			Files.writeString(storage.activePath("mods/players.jar"), "player added", StandardCharsets.UTF_8);
			String hash = pl.skidam.automodpack_core.utils.HashUtils.sha1("player added".getBytes(StandardCharsets.UTF_8));
			boolean[] seen = {false};
			converge(storage, cache, NEW, Map.of(), new ProjectionConvergence.Writes() {
				@Override
				public void acquiring(Set<String> hashes) throws IOException {
					seen[0] = true;
					assertTrue(hashes.contains(hash));
					assertFalse(Files.exists(storage.objectFile(hash)), "pinned before it exists");
					assertTrue(receipt(storage).contains(hash));
				}
			});
			assertTrue(seen[0]);
			assertTrue(Files.exists(storage.objectFile(hash)));
			assertTrue(receipt(storage).contains(hash), "the converging state pins it after the mark");
		}
	}

	/** F8: a writer outside the commit that re-links the projection moves the revision and forgets the content digest. */
	@Test
	void anOutsideRelinkMovesTheRevision() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
		}
		ProjectionState before = ProjectionState.read(storage);
		assertNotNull(before.contentDigest());
		ProjectionState.touched(storage);
		ProjectionState after = ProjectionState.read(storage);
		assertTrue(after.revision() > before.revision());
		assertNull(after.contentDigest());
		assertFalse(after.sameContentAs(before));
	}

	/**
	 * A case-only rename, a directory case change, a version bump and a file-to-directory change, then a crash before
	 * each write and a restore to the exact old names. On a case-insensitive mount with the store on another volume
	 * this runs the verified-copy path.
	 */
	@Test
	void caseOnlyRenamesAndShapeChangesRestoreExactlyAtEveryCrash() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		Map<String, String> before = Map.of("mods/Sodium.jar", "sodium", "Config/x.toml", "x", "mods/lithium-1.0.jar", "l1", "shaderpacks/pack.zip", "zip");
		Map<String, String> after = Map.of("mods/sodium.jar", "sodium", "config/x.toml", "x", "mods/lithium-1.1.jar", "l2", "shaderpacks/pack.zip/inner.txt", "dir");
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, before);
			assertEquals(before, tree(storage));
			assertTrue(converge(storage, cache, after, Map.of(), ProjectionConvergence.Writes.NONE).converged());
			ProjectionState.settled(storage, "tx");
			assertEquals(List.of("lithium-1.1.jar", "sodium.jar"), names(storage.activePath("mods")));
			assertTrue(names(storage.activeDirectory()).contains("config"), names(storage.activeDirectory()).toString());
			assertEquals(after, tree(storage));
			assertFalse(converge(storage, cache, after, Map.of(), ProjectionConvergence.Writes.NONE).changed(), "a second run is a no-op");

			for (int crashAt = 0; crashAt < 12; crashAt++) {
				settle(storage, cache, before);
				assertEquals(before, tree(storage));
				int[] seen = {0};
				int at = crashAt;
				try {
					converge(storage, cache, after, Map.of(), new ProjectionConvergence.Writes() {
						@Override
						public void beforeWrite(Path target) {
							if (seen[0]++ == at) throw new IllegalStateException("crash");
						}
					});
				} catch (IllegalStateException crashed) {
					assertNotNull(ProjectionState.loadRefusal(storage.gameDirectory()));
					assertTrue(new ProjectionConvergence(storage, cache, ProjectionConvergence.Writes.NONE).restoreFallback());
					assertEquals(before, tree(storage), "restore at crash " + at);
					assertTrue(names(storage.activePath("mods")).contains("Sodium.jar"), "exact case restored at " + at + ": " + names(storage.activePath("mods")));
					assertEquals(List.of(), repairBackups(storage).keySet().stream().toList(), "nothing the store lacks was destroyed");
					continue;
				}
				ProjectionState.settled(storage, "tx" + at);
				assertEquals(after, tree(storage));
			}
		}
	}

	/**
	 * R2-1: a read failure that persists is never a held file and never bricks: this boot refuses and names the file,
	 * and the next boot sets the document aside and runs the non-destructive repair.
	 */
	@Test
	void aPersistentReadFailureIsRepairedAtTheNextBootAndIsNeverAHeldFile() throws Exception {
		Assumptions.assumeTrue(Files.getFileAttributeView(temporaryDirectory, PosixFileAttributeView.class) != null);
		Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
		}
		Path state = storage.projectionStateFile();
		Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("---------"));
		Assumptions.assumeFalse(Files.isReadable(state), "this filesystem ignores permissions");

		new pl.skidam.automodpack_core.client.BootRecovery(storage).recover();
		assertNotNull(ProjectionState.loadRefusal(storage.gameDirectory()), "the first boot that meets it refuses");
		assertTrue(Files.exists(state.resolveSibling(ProjectionState.READ_FAILURE_FILE)), "and records it");
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			IOException update = assertThrows(IOException.class, () -> converge(storage, cache, NEW, Map.of(), ProjectionConvergence.Writes.NONE));
			assertInstanceOf(ProjectionState.UnavailableException.class, update);
			assertTrue(update.getMessage().contains(state.toString()), update.getMessage());
			assertFalse(UpdateCommit.isLockFailure(update, true), "never a held file, not even on Windows");
			assertFalse(UpdateCommit.isLockFailure(update, false));
		}

		ProjectionState.assumeNextBoot();
		new pl.skidam.automodpack_core.client.BootRecovery(storage).recover();
		assertNull(ProjectionState.loadRefusal(storage.gameDirectory()), "the next boot repaired it");
		assertEquals(OLD, tree(storage), "without touching the projection");
		assertFalse(Files.exists(state.resolveSibling(ProjectionState.READ_FAILURE_FILE)));
		assertEquals(1, asides(storage).size(), "the unreadable document is kept aside");
	}

	/** R2-2: full and read-only are decided by the store, in any language; the same message on a store with room is a held file. */
	@Test
	void aFullDiskIsDecidedByTheStoreInAnyLanguage() throws Exception {
		Path file = Files.createFile(temporaryDirectory.resolve("a.jar"));
		List<String> localized = List.of("Brak miejsca na urządzeniu", "Za mało miejsca na dysku.", "Kein Speicherplatz mehr verfügbar");
		StorageConditions.Stores real = StorageConditions.stores;
		try {
			StorageConditions.stores = existing -> new StorageConditions.Store(false, 512 * 1024);
			for (String reason : localized) {
				FileSystemException failure = new FileSystemException(file.toString(), null, reason);
				assertFalse(UpdateCommit.isLockFailure(failure, false, 64 * 1024), reason);
				assertFalse(UpdateCommit.isLockFailure(failure, true, 64 * 1024), reason);
				assertTrue(StorageConditions.condition(failure, 64 * 1024).startsWith("the disk is full"));
			}
			StorageConditions.stores = existing -> new StorageConditions.Store(false, 10L << 30);
			assertTrue(StorageConditions.condition(new FileSystemException(file.toString(), null, "Brak miejsca na urządzeniu"), 64 * 1024) == null, "room on the store: not full");
			assertTrue(UpdateCommit.isLockFailure(new FileSystemException(file.toString(), null, "Proces nie może uzyskać dostępu do pliku, ponieważ jest on używany przez inny proces."), true));
			assertNotNull(StorageConditions.condition(new FileSystemException(file.toString(), null, "Brak miejsca"), (10L << 30)), "a write larger than the free space is full");
			assertTrue(UpdateCommit.isLockFailure(new FileSystemException(file.toString(), null, "Locking not supported by this driver"), true), "no bare substring decides");
			StorageConditions.stores = existing -> new StorageConditions.Store(true, 10L << 30);
			assertEquals("the filesystem is read-only", StorageConditions.condition(new FileSystemException(file.toString(), null, "System plików tylko do odczytu")));
		} finally {
			StorageConditions.stores = real;
		}
	}

	/** R2-3: the guard is keyed to what it relaunched for, cleared by every boot that needs no relaunch, and never stale. */
	@Test
	void theRelaunchGuardIsKeyedToItsRelaunchAndClearedByAnyBootThatNeedsNone() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
		}
		ProjectionState state = ProjectionState.read(storage);
		ClientStorage.DirectoryScan refused = new ClientStorage.DirectoryScan(state, true, false);
		assertTrue(ProjectionRelaunch.required(storage, refused));
		assertFalse(ProjectionRelaunch.required(storage, refused), "the same need right after its relaunch does not relaunch again");
		assertTrue(Files.exists(storage.projectionRelaunchFile()));

		assertFalse(ProjectionRelaunch.required(storage, null), "a boot without a directory scan needs none");
		assertFalse(Files.exists(storage.projectionRelaunchFile()), "and clears the guard");
		assertTrue(ProjectionRelaunch.required(storage, refused), "so a later need relaunches");

		// A guard for other content from long ago never suppresses; one from a moment ago does (one relaunch in a row).
		Files.writeString(storage.projectionRelaunchFile(), "{\"content\":\"digest:other\",\"at\":" + (System.currentTimeMillis() - 2 * ProjectionRelaunch.CONSECUTIVE_MILLIS) + "}");
		assertTrue(ProjectionRelaunch.required(storage, refused));
		Files.writeString(storage.projectionRelaunchFile(), "{\"content\":\"digest:other\",\"at\":" + System.currentTimeMillis() + "}");
		assertFalse(ProjectionRelaunch.required(storage, refused));
		assertFalse(ProjectionRelaunch.required(storage, new ClientStorage.DirectoryScan(state, false, true)));
		assertFalse(Files.exists(storage.projectionRelaunchFile()), "a clean scan clears it");
	}

	/** R2-3: a transient read failure at scan time is retried, so it neither refuses Kilt nor spends a relaunch. */
	@Test
	void aTransientReadFailureAtScanTimeIsRetried() throws Exception {
		Assumptions.assumeTrue(Files.getFileAttributeView(temporaryDirectory, PosixFileAttributeView.class) != null);
		Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
		}
		Path state = storage.projectionStateFile();
		Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("---------"));
		Assumptions.assumeFalse(Files.isReadable(state), "this filesystem ignores permissions");
		Thread release = new Thread(() -> {
			try {
				Thread.sleep(150);
				Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rw-r--r--"));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		release.start();
		try {
			assertNotNull(ClientStorage.loadableProjectionModsDirectory(storage.gameDirectory()));
			assertTrue(ClientStorage.directoryScan().exposed());
		} finally {
			release.join();
			Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rw-r--r--"));
		}
		assertFalse(ProjectionRelaunch.required(storage, ClientStorage.directoryScan()));
	}

	private static void commit(ClientStorage storage) throws Exception {
		byte[] bytes = "committed".getBytes(StandardCharsets.UTF_8);
		String hash = OverlayTombstoneCommitTest.store(storage, bytes);
		var target = UpdateTestFixtures.target(storage, "mods/committed.jar", "mod", false, hash, bytes.length);
		storage.writeActiveState(target.document(), target.selection().intent());
	}

	private static String replaceWithARecordedCopyAndDropTheObject(ClientStorage storage, FileCache cache, String path, String content) throws Exception {
		Path file = storage.activePath(path);
		String hash = rows(storage, Map.of(path, content)).get(0).sha1();
		Path copy = file.resolveSibling(file.getFileName() + ".copy");
		Files.copy(file, copy);
		ImmutableFiles.unprotect(file);
		Files.delete(file);
		Files.move(copy, file);
		cache.overwriteCache(file, hash);
		dropObject(storage, hash);
		return hash;
	}

	private static void dropObject(ClientStorage storage, String hash) throws IOException {
		Path object = storage.objectFile(hash);
		ImmutableFiles.unprotect(object);
		Files.delete(object);
	}

	private static Set<String> receipt(ClientStorage storage) throws IOException {
		Set<String> hashes = new HashSet<>();
		try (Stream<Path> receipts = Files.list(storage.dataLocation().layout().objectOwnersDirectory())) {
			for (Path path : receipts.filter(path -> path.getFileName().toString().endsWith(".client.json")).toList())
				hashes.addAll(ConfigTools.read(path, StorageJsons.ObjectOwnershipFields.class).orElseThrow().objectHashes);
		}
		return hashes;
	}

	private static ProjectionConvergence.Result converge(ClientStorage storage, FileCache cache, Map<String, String> files, Map<String, String> hints, ProjectionConvergence.Writes writes)
			throws Exception {
		return new ProjectionConvergence(storage, cache, writes).converge("tx", rows(storage, files), hints);
	}

	private static ProjectionConvergence.Writes holding(Predicate<Path> held) {
		return new ProjectionConvergence.Writes() {
			@Override
			public void beforeWrite(Path target) throws IOException {
				if (held.test(target)) throw new FileSystemException(target.toString(), null, "The process cannot access the file because it is being used by another process");
			}
		};
	}

	private static void settle(ClientStorage storage, FileCache cache, Map<String, String> files) throws Exception {
		assertTrue(converge(storage, cache, files, Map.of(), ProjectionConvergence.Writes.NONE).converged());
		ProjectionState.settled(storage, "settle-" + UUID.randomUUID());
	}

	private static List<ProjectionState.Row> rows(ClientStorage storage, Map<String, String> files) throws Exception {
		List<ProjectionState.Row> rows = new ArrayList<>();
		for (var entry : new TreeMap<>(files).entrySet()) {
			byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
			rows.add(new ProjectionState.Row(entry.getKey(), OverlayTombstoneCommitTest.store(storage, bytes), bytes.length));
		}
		return rows;
	}

	private static Map<String, String> tree(ClientStorage storage) throws IOException {
		return files(storage.activeDirectory());
	}

	private static Map<String, String> repairBackups(ClientStorage storage) throws IOException {
		Map<String, String> kept = new TreeMap<>();
		try (Stream<Path> entries = Files.list(storage.clientDirectory())) {
			for (Path backup : entries.filter(path -> path.getFileName().toString().startsWith(ProjectionConvergence.REPAIR_BACKUP_PREFIX)).toList()) kept.putAll(files(backup));
		}
		return kept;
	}

	private static List<String> asides(ClientStorage storage) throws IOException {
		try (Stream<Path> entries = Files.list(storage.clientDirectory())) {
			return entries.map(path -> path.getFileName().toString()).filter(name -> name.startsWith("projection-state.json.corrupt-")).toList();
		}
	}

	private static Map<String, String> files(Path root) throws IOException {
		Map<String, String> files = new TreeMap<>();
		if (!Files.exists(root)) return files;
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList())
				files.put(root.relativize(path).toString().replace('\\', '/'), Files.readString(path));
		}
		return files;
	}

	private static List<String> names(Path directory) throws IOException {
		try (Stream<Path> list = Files.list(directory)) {
			return list.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}
}
