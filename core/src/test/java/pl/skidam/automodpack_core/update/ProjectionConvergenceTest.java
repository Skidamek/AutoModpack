package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import pl.skidam.automodpack_core.utils.cache.FileCache;

/** V1-V5 of {@link ProjectionConvergence}: one whole-tree convergence from CAS, re-runnable from any crash, in either direction. */
class ProjectionConvergenceTest {
	@TempDir
	Path temporaryDirectory;

	private static final class Crash extends Error {}

	/** The old pack: a mod, a config, a nested resource, and a directory the new pack turns into a file. */
	private static final Map<String, String> OLD = Map.of(
			"mods/alpha-1.0.jar", "alpha one",
			"mods/beta.jar", "beta",
			"config/alpha.toml", "a = 1",
			"resourcepacks/pack/assets/x.json", "{}",
			"shaderpacks/shader.zip", "shader dir parent");

	/** The new pack: a version bump, a case-only rename, a changed config, a directory-to-file and a file-to-directory transition. */
	private static final Map<String, String> NEW = Map.of(
			"mods/alpha-1.1.jar", "alpha two",
			"mods/Beta.jar", "beta",
			"config/alpha.toml", "a = 2",
			"resourcepacks/pack", "now a zip",
			"shaderpacks/shader.zip/inner.txt", "now a directory");

	@Test
	void convergesOntoTheWholeTargetAndASecondRunWritesNothing() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			assertEquals(OLD, tree(storage));
			long revision = ProjectionState.read(storage).revision();

			ProjectionConvergence.Result result = converge(storage, cache, NEW, ProjectionConvergence.Writes.NONE);
			assertTrue(result.changed());
			assertTrue(result.converged());
			assertEquals(NEW, tree(storage));
			assertTrue(ProjectionState.read(storage).converging(), "a convergence settles only with its commit");
			ProjectionState.settled(storage, "next");

			long settled = ProjectionState.read(storage).revision();
			assertTrue(settled > revision);
			ProjectionConvergence.Result again = converge(storage, cache, NEW, ProjectionConvergence.Writes.NONE);
			assertFalse(again.changed());
			assertEquals(settled, ProjectionState.read(storage).revision(), "nothing to do marks nothing");
		}
	}

	/** C6: a crash before every single write leaves a tree every gate refuses, and both directions finish from there. */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
	void aCrashBeforeAnyWriteIsRefusedThenEitherRestoredOrFinished(int crashAt) throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			int writes = countWrites(storage, cache);
			if (crashAt >= writes) return;
			int[] seen = {0};
			assertThrows(Crash.class, () -> converge(storage, cache, NEW, new ProjectionConvergence.Writes() {
				@Override
				public void beforeWrite(Path target) {
					if (seen[0]++ == crashAt) throw new Crash();
				}
			}));
			assertTrue(ProjectionState.read(storage).converging(), "marked before the first write");
			assertNotNull(ProjectionState.loadRefusal(storage.gameDirectory()), "no gate loads a mixed tree");
			assertNull(ClientStorage.loadableProjectionModsDirectory(storage.gameDirectory()));
			ClientStorage.forgetDirectoryScan();

			// Backward: the fallback is the complete old tree, put back from CAS.
			assertTrue(new ProjectionConvergence(storage, cache, ProjectionConvergence.Writes.NONE).restoreFallback());
			assertEquals(OLD, tree(storage));
			assertNull(ProjectionState.loadRefusal(storage.gameDirectory()));

			// Forward from a second crash at the same write: the fallback the first run recorded survives the rerun.
			seen[0] = 0;
			assertThrows(Crash.class, () -> converge(storage, cache, NEW, new ProjectionConvergence.Writes() {
				@Override
				public void beforeWrite(Path target) {
					if (seen[0]++ == crashAt) throw new Crash();
				}
			}));
			assertTrue(converge(storage, cache, NEW, ProjectionConvergence.Writes.NONE).converged());
			assertEquals(NEW, tree(storage));
			assertEquals(rows(storage, OLD), sorted(ProjectionState.read(storage).fallback()));
		}
	}

	/** C5: every old name goes before any new one lands, so no moment holds two versions of one mod. */
	@Test
	void everyRemovalPrecedesEveryInstall() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			List<String> order = new ArrayList<>();
			converge(storage, cache, NEW, new ProjectionConvergence.Writes() {
				@Override
				public void beforeWrite(Path target) {
					order.add((Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? "-" : "+") + storage.activeDirectory().relativize(target).toString().replace('\\', '/'));
				}
			});
			assertTrue(order.indexOf("-mods/alpha-1.0.jar") < order.indexOf("+mods/alpha-1.1.jar"), String.valueOf(order));
			int lastRemoval = 0;
			for (int i = 0; i < order.size(); i++) if (order.get(i).startsWith("-")) lastRemoval = i;
			for (int i = 0; i < lastRemoval; i++) assertTrue(order.get(i).startsWith("-"), "an install before a removal: " + order);
		}
	}

	/** C5: a case-only rename ends with the exact new name, on a case-insensitive filesystem too, file and directory alike. */
	@Test
	void aCaseOnlyRenameEndsWithTheExactNewName() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, Map.of("mods/Sodium.jar", "s", "Config/x.toml", "x"));
			converge(storage, cache, Map.of("mods/sodium.jar", "s", "config/x.toml", "x"), ProjectionConvergence.Writes.NONE);
			assertEquals(List.of("sodium.jar"), names(storage.activePath("mods")));
			assertEquals(List.of("config", "mods"), names(storage.activeDirectory()));
			assertEquals(Map.of("mods/sodium.jar", "s", "config/x.toml", "x"), tree(storage));
		}
	}

	/** C6 (Windows): a held path stays owed while everything else lands, the state stays converging, and the fallback waits for the hold. */
	@Test
	void aHeldPathStaysOwedWhileTheRestLands() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			Path held = storage.activePath("mods/alpha-1.0.jar");
			ProjectionConvergence.Result result = converge(storage, cache, NEW, holding(path -> path.equals(held)));

			assertFalse(result.converged());
			assertEquals(List.of(held), result.held().stream().map(ProjectionConvergence.Held::path).toList());
			assertTrue(Files.exists(held));
			assertEquals("alpha two", Files.readString(storage.activePath("mods/alpha-1.1.jar")), "the rest landed");
			assertNotNull(ProjectionState.loadRefusal(storage.gameDirectory()));

			assertTrue(new ProjectionConvergence(storage, cache, ProjectionConvergence.Writes.NONE).restoreFallback());
			assertEquals(OLD, tree(storage));
		}
	}

	/** A held path under a directory the target turns into a file keeps that install owed instead of failing the run. */
	@Test
	void aHeldPathInsideADirectoryTheTargetReplacesKeepsTheReplacementOwed() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			Path held = storage.activePath("resourcepacks/pack/assets/x.json");
			ProjectionConvergence.Result result = converge(storage, cache, NEW, holding(path -> path.equals(held)));

			assertEquals(List.of(held, storage.activePath("resourcepacks/pack")), result.held().stream().map(ProjectionConvergence.Held::path).toList());
			assertTrue(converge(storage, cache, NEW, ProjectionConvergence.Writes.NONE).converged());
			assertEquals(NEW, tree(storage));
		}
	}

	/** A file no object holds - one a player dropped in - is acquired into CAS for the fallback, so a restore can put it back. */
	@Test
	void theFallbackKeepsBytesNoObjectHeld() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			Files.writeString(storage.activePath("mods/dropped.jar"), "players own", StandardCharsets.UTF_8);
			converge(storage, cache, NEW, ProjectionConvergence.Writes.NONE);
			assertFalse(Files.exists(storage.activePath("mods/dropped.jar")));

			assertTrue(new ProjectionConvergence(storage, cache, ProjectionConvergence.Writes.NONE).restoreFallback());
			assertEquals("players own", Files.readString(storage.activePath("mods/dropped.jar")));
		}
	}

	/** A first install writes straight into a new {@code active/}; its fallback is no projection at all. */
	@Test
	void aFirstInstallFallsBackToNoProjection() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			assertThrows(Crash.class, () -> converge(storage, cache, NEW, new ProjectionConvergence.Writes() {
				int seen;

				@Override
				public void beforeWrite(Path target) {
					if (seen++ == 2) throw new Crash();
				}
			}));
			assertTrue(Files.isDirectory(storage.activeDirectory()));
			assertNotNull(ProjectionState.loadRefusal(storage.gameDirectory()), "a half first install is refused too");

			assertTrue(new ProjectionConvergence(storage, cache, ProjectionConvergence.Writes.NONE).restoreFallback());
			assertFalse(Files.exists(storage.activeDirectory()));
			assertNull(ProjectionState.loadRefusal(storage.gameDirectory()));
		}
	}

	/** P4/GC: the objects a converging fallback names are retained by collection until it settles. */
	@Test
	void collectionRetainsTheFallback() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			converge(storage, cache, NEW, ProjectionConvergence.Writes.NONE);
		}
		ClientObjectStore.collectUnreachableObjects(storage, java.util.Set.of());
		for (ProjectionState.Row row : ProjectionState.read(storage).fallback()) assertTrue(Files.isRegularFile(storage.objectFile(row.sha1())), row.path());
	}

	/** Symbolic links and special files are never projection content: removed on the way, and never part of a fallback. */
	@Test
	void aSymbolicLinkIsRemovedAndNeverRestored() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			Path outside = Files.writeString(temporaryDirectory.resolve("outside.jar"), "outside");
			try {
				Files.createSymbolicLink(storage.activePath("mods/link.jar"), outside);
			} catch (UnsupportedOperationException | IOException e) {
				return;
			}
			converge(storage, cache, NEW, ProjectionConvergence.Writes.NONE);
			assertFalse(Files.exists(storage.activePath("mods/link.jar"), LinkOption.NOFOLLOW_LINKS));
			assertTrue(new ProjectionConvergence(storage, cache, ProjectionConvergence.Writes.NONE).restoreFallback());
			assertEquals(OLD, tree(storage));
			assertEquals("outside", Files.readString(outside));
		}
	}

	/** P4 at boot: a converging projection with no journal left to finish it is restored before any loader gate runs. */
	@Test
	void bootRecoveryRestoresAConvergingProjectionNoJournalOwns() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			settle(storage, cache, OLD);
			assertThrows(Crash.class, () -> converge(storage, cache, NEW, new ProjectionConvergence.Writes() {
				int seen;

				@Override
				public void beforeWrite(Path target) {
					if (seen++ == 3) throw new Crash();
				}
			}));
		}
		assertFalse(Files.exists(storage.transactionFile()));

		new pl.skidam.automodpack_core.client.BootRecovery(storage).recover();

		assertNull(ProjectionState.loadRefusal(storage.gameDirectory()));
		assertEquals(OLD, tree(storage));
	}

	/** P6: an unreadable state is owed work, and its repair rebuilds the projection of the generation the active state names. */
	@Test
	void anUnreadableStateRebuildsTheCommittedGeneration() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		byte[] bytes = "committed".getBytes(StandardCharsets.UTF_8);
		String hash = OverlayTombstoneCommitTest.store(storage, bytes);
		var target = UpdateTestFixtures.target(storage, "mods/committed.jar", "mod", false, hash, bytes.length);
		storage.writeActiveState(target.document(), target.selection().intent());
		Files.createDirectories(storage.activePath("mods"));
		Files.writeString(storage.activePath("mods/stray.jar"), "half an update", StandardCharsets.UTF_8);
		Files.writeString(storage.projectionStateFile(), "{torn", StandardCharsets.UTF_8);
		assertTrue(ProjectionState.owed(storage));
		assertNotNull(ProjectionState.loadRefusal(storage.gameDirectory()));

		assertTrue(PublicationSweep.restoreFallback(storage));

		assertEquals(Map.of("mods/committed.jar", "committed"), tree(storage));
		assertFalse(ProjectionState.owed(storage));
		try (Stream<Path> aside = Files.list(storage.projectionStateFile().getParent())) {
			assertTrue(aside.anyMatch(path -> path.getFileName().toString().startsWith("projection-state.json.")), "the unreadable document is kept as evidence");
		}
	}

	private int countWrites(ClientStorage storage, FileCache cache) throws Exception {
		Path probe = temporaryDirectory.resolve("count");
		ClientStorage scratch = UpdateTestFixtures.storage(probe);
		try (FileCache scratchCache = FileCache.open(scratch.fileCacheDirectory())) {
			settle(scratch, scratchCache, OLD);
			int[] seen = {0};
			converge(scratch, scratchCache, NEW, new ProjectionConvergence.Writes() {
				@Override
				public void beforeWrite(Path target) {
					seen[0]++;
				}
			});
			return seen[0];
		}
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
		assertTrue(converge(storage, cache, files, ProjectionConvergence.Writes.NONE).converged());
		ProjectionState.settled(storage, "settle");
	}

	private static ProjectionConvergence.Result converge(ClientStorage storage, FileCache cache, Map<String, String> files, ProjectionConvergence.Writes writes) throws Exception {
		return new ProjectionConvergence(storage, cache, writes).converge("tx", rows(storage, files), Map.of());
	}

	private static List<ProjectionState.Row> rows(ClientStorage storage, Map<String, String> files) throws Exception {
		List<ProjectionState.Row> rows = new ArrayList<>();
		for (var entry : new TreeMap<>(files).entrySet()) {
			byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
			rows.add(new ProjectionState.Row(entry.getKey(), OverlayTombstoneCommitTest.store(storage, bytes), bytes.length));
		}
		return rows;
	}

	private static List<ProjectionState.Row> sorted(List<ProjectionState.Row> rows) {
		return rows.stream().sorted(java.util.Comparator.comparing(ProjectionState.Row::path)).toList();
	}

	private static Map<String, String> tree(ClientStorage storage) throws IOException {
		Map<String, String> files = new TreeMap<>();
		Path active = storage.activeDirectory();
		if (!Files.exists(active)) return files;
		try (Stream<Path> walk = Files.walk(active)) {
			for (Path path : walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList())
				files.put(active.relativize(path).toString().replace('\\', '/'), Files.readString(path));
		}
		return files;
	}

	private static List<String> names(Path directory) throws IOException {
		try (Stream<Path> list = Files.list(directory)) {
			return list.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}
}
