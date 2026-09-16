package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

class ClientOverlaySnapshotTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void identicalOverlayTombstonesDoNotReplaceStateFile() throws Exception {
		ClientStorage storage = storage();
		storage.writeOverlayState("abcdefg", Set.of("config/deleted.txt"));
		Path stateFile = storage.overlayStateFile("abcdefg");
		String equivalentState = "{\n  \"modpackId\": \"abcdefg\",\n  \"deletedPaths\": [\"config/deleted.txt\"],\n  \"sentinel\": \"must-survive\"\n}\n";
		Files.writeString(stateFile, equivalentState, StandardCharsets.UTF_8);
		BasicFileAttributes before = Files.readAttributes(stateFile, BasicFileAttributes.class);
		byte[] contents = Files.readAllBytes(stateFile);

		storage.writeOverlayState("abcdefg", Set.of("config/deleted.txt"));

		BasicFileAttributes after = Files.readAttributes(stateFile, BasicFileAttributes.class);
		if (before.fileKey() != null && after.fileKey() != null) assertEquals(before.fileKey(), after.fileKey());
		assertArrayEquals(contents, Files.readAllBytes(stateFile));
	}

	@Test
	void snapshotReusesMetadataCacheAndPreservesDigestSemantics() throws Exception {
		ClientStorage storage = storage();
		Path overlay = storage.overlayFile("abcdefg", "config/example.txt");
		Files.createDirectories(overlay.getParent());
		Files.writeString(overlay, "overlay");
		storage.writeOverlayState("abcdefg", Set.of("config/deleted.txt"));

		try (FileMetadataCache cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			ClientOverlaySnapshot first = storage.overlaySnapshot("abcdefg", cache);
			Map<Path, BasicFileAttributes> metadataBefore = metadataRecords(storage.fileMetadataDirectory());
			ClientOverlaySnapshot second = storage.overlaySnapshot("abcdefg", cache);
			Map<Path, BasicFileAttributes> metadataAfter = metadataRecords(storage.fileMetadataDirectory());

			assertEquals(first, second);
			assertEquals(first.digest(), storage.overlayDigest("abcdefg"));
			assertEquals(metadataBefore.keySet(), metadataAfter.keySet());
			for (Path path : metadataBefore.keySet()) assertEquals(metadataBefore.get(path).fileKey(), metadataAfter.get(path).fileKey());
		}
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = ClientStorage.fromGameDirectory(temporaryDirectory.resolve("game"));
		storage.ensureRoots();
		return storage;
	}

	private static Map<Path, BasicFileAttributes> metadataRecords(Path root) throws Exception {
		try (var paths = Files.walk(root)) {
			return paths.filter(path -> Files.isRegularFile(path)).collect(java.util.stream.Collectors.toMap(path -> path, path -> readAttributes(path)));
		}
	}

	private static BasicFileAttributes readAttributes(Path path) {
		try {
			return Files.readAttributes(path, BasicFileAttributes.class);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
