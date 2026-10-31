package pl.skidam.automodpack_core.update;

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
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.utils.cache.FileCache;

/** The projection suites' harness: one tree as CAS rows, one convergence run, one settle, and injected holds. */
final class ProjectionTestFixtures {
	private ProjectionTestFixtures() {}

	static ProjectionConvergence.Result converge(ClientStorage storage, FileCache cache, Map<String, String> files, ProjectionConvergence.Writes writes) throws Exception {
		return converge(storage, cache, files, Map.of(), writes);
	}

	static ProjectionConvergence.Result converge(ClientStorage storage, FileCache cache, Map<String, String> files, Map<String, String> hints, ProjectionConvergence.Writes writes)
			throws Exception {
		return new ProjectionConvergence(storage, cache, writes).converge("tx", rows(storage, files), hints);
	}

	static void settle(ClientStorage storage, FileCache cache, Map<String, String> files) throws Exception {
		assertTrue(converge(storage, cache, files, ProjectionConvergence.Writes.NONE).converged());
		ProjectionState.settled(storage, "settle-" + UUID.randomUUID());
	}

	static List<ProjectionState.Row> rows(ClientStorage storage, Map<String, String> files) throws Exception {
		List<ProjectionState.Row> rows = new ArrayList<>();
		for (var entry : new TreeMap<>(files).entrySet()) {
			byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
			rows.add(new ProjectionState.Row(entry.getKey(), UpdateTestFixtures.store(storage, bytes), bytes.length));
		}
		return rows;
	}

	/** A writer that fails the paths {@code held} accepts with the sharing violation a held file produces on Windows. */
	static ProjectionConvergence.Writes holding(Predicate<Path> held) {
		return new ProjectionConvergence.Writes() {
			@Override
			public void beforeWrite(Path target) throws IOException {
				if (held.test(target)) throw new FileSystemException(target.toString(), null, "The process cannot access the file because it is being used by another process");
			}
		};
	}

	/** Every regular file under {@code root} by its slash-joined relative path. */
	static Map<String, String> files(Path root) throws IOException {
		Map<String, String> files = new TreeMap<>();
		if (!Files.exists(root)) return files;
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList())
				files.put(root.relativize(path).toString().replace('\\', '/'), Files.readString(path));
		}
		return files;
	}

	static Map<String, String> tree(ClientStorage storage) throws IOException {
		return files(storage.activeDirectory());
	}

	static List<String> names(Path directory) throws IOException {
		try (Stream<Path> list = Files.list(directory)) {
			return list.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}
}
