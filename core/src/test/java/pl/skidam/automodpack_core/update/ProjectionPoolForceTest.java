package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * V5 under the pool: every directory this run creates has its parent forced before the run returns. The pool's
 * {@code ensuredDirectories.add(parent)} happens before {@code createDirectories(parent)}, so a second worker can
 * skip the creation and let the link fallback create the chain without recording its parents as touched.
 */
class ProjectionPoolForceTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void everyDirectoryCreatedByThePoolHasItsParentForced() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		Map<String, String> target = new LinkedHashMap<>();
		for (int directory = 0; directory < 80; directory++)
			for (int file = 0; file < 2; file++)
				target.put("d" + directory + "/a/b/c/d/f" + file + ".txt", "bytes " + directory + "/" + file);
		Set<Path> existedBefore = directories(storage.activeDirectory());
		Set<Path> forced = new LinkedHashSet<>();
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			ProjectionConvergence.Result result = ProjectionTestFixtures.converge(storage, cache, target, new ProjectionConvergence.Writes() {
				@Override
				public void forced(Path directory) {
					forced.add(directory.toAbsolutePath().normalize());
				}

				@Override
				public boolean parallelSafe() {
					return true;
				}
			});
			assertTrue(result.converged(), String.valueOf(result.held()));
		}
		Set<Path> created = directories(storage.activeDirectory());
		created.removeAll(existedBefore);
		assertTrue(!created.isEmpty(), "the run must create directories");
		for (Path directory : created) {
			Path parent = directory.getParent();
			if (parent != null) assertTrue(forced.contains(parent), "created directory's parent was never forced: " + directory + " (parent " + parent + ")");
		}
	}

	private static Set<Path> directories(Path root) throws Exception {
		Set<Path> directories = new LinkedHashSet<>();
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return directories;
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.toList()) if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) directories.add(path.toAbsolutePath().normalize());
		}
		return directories;
	}
}
