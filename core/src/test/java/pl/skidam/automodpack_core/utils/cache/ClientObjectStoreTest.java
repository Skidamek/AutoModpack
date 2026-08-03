package pl.skidam.automodpack_core.utils.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.HashUtils;

class ClientObjectStoreTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void collectsUnreachableObjectsAndAbandonedStagingFiles() throws Exception {
		Path objects = temporaryDirectory.resolve("objects");
		Path staging = temporaryDirectory.resolve("staging");
		Files.createDirectories(objects);
		Files.createDirectories(staging);
		Path retainedSource = Files.writeString(temporaryDirectory.resolve("retained-source"), "retained", StandardCharsets.UTF_8);
		Path orphanSource = Files.writeString(temporaryDirectory.resolve("orphan-source"), "orphan", StandardCharsets.UTF_8);
		String retainedHash = HashUtils.getHash(retainedSource);
		String orphanHash = HashUtils.getHash(orphanSource);
		Files.copy(retainedSource, objects.resolve(retainedHash));
		Files.copy(orphanSource, objects.resolve(orphanHash));
		Path abandoned = Files.writeString(staging.resolve(".download.tmp"), "partial", StandardCharsets.UTF_8);

		ClientObjectStore.CollectionResult result = new ClientObjectStore(objects, staging).collect(Set.of(retainedHash));

		assertTrue(Files.exists(objects.resolve(retainedHash)));
		assertFalse(Files.exists(objects.resolve(orphanHash)));
		assertFalse(Files.exists(abandoned));
		assertEquals(1, result.objectsDeleted());
		assertEquals(1, result.stagingFilesDeleted());
	}
}
