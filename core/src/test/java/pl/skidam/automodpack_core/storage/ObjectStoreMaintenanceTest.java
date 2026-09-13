package pl.skidam.automodpack_core.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.HashUtils;

class ObjectStoreMaintenanceTest {
	@TempDir
	Path tempDir;

	private Path writeObject(Path objectsDirectory, String hash, String content) throws IOException {
		Path file = DataRootResolver.objectFile(objectsDirectory, hash);
		Files.createDirectories(file.getParent());
		return Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private String hashOf(String content) throws IOException {
		return HashUtils.getHash(Files.writeString(tempDir.resolve(content), content, StandardCharsets.UTF_8));
	}

	@Test
	void deleteUnreachableDeletesBitRotatedOrphansByReachabilityAlone() throws IOException {
		Path objects = tempDir.resolve("objects");
		String keptHash = hashOf("kept");
		String orphanHash = hashOf("gone");
		Path kept = writeObject(objects, keptHash, "kept");
		Path rottedOrphan = writeObject(objects, orphanHash, "bytes that never matched the orphan name");
		long rottedBytes = Files.size(rottedOrphan);

		ObjectStoreMaintenance.DeletionReceipt receipt = ObjectStoreMaintenance.deleteUnreachable(objects, Set.of(keptHash));

		assertEquals(new ObjectStoreMaintenance.DeletionReceipt(1, rottedBytes), receipt);
		assertTrue(Files.exists(kept));
		assertFalse(Files.exists(rottedOrphan));
	}

	@Test
	void deleteUnreachableKeepsReachableObjectsWithoutReadingTheirBytes() throws IOException {
		Path objects = tempDir.resolve("objects");
		String hash = hashOf("advertised");
		Path rotted = writeObject(objects, hash, "rotted bytes");

		ObjectStoreMaintenance.deleteUnreachable(objects, Set.of(hash));

		assertTrue(Files.exists(rotted));
	}
}
