package pl.skidam.automodpack_core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageLeftoversTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void v4LeftoversAndMigrationBackupsAreFlaggedWhileTheOwnedLayoutStaysSilent() throws IOException {
		Path automodpack = Files.createDirectories(temporaryDirectory.resolve("automodpack"));
		Files.createDirectories(automodpack.resolve("modpacks").resolve("My Pack"));
		Files.createDirectories(automodpack.resolve(".private"));
		Files.createFile(automodpack.resolve("automodpack-client.json"));
		Files.createFile(automodpack.resolve("automodpack-client.json.backup"));
		Files.createFile(automodpack.resolve("automodpack-content.json.temp"));
		Files.createFile(automodpack.resolve("automodpack-dummy-files.json"));
		Files.createFile(automodpack.resolve("automodpack-server.json.backup-2"));
		Files.createDirectories(automodpack.resolve("client").resolve("active"));
		Files.createFile(automodpack.resolve("client.conf"));
		Files.createFile(automodpack.resolve("self-update.json.corrupt-1d2e3f"));

		assertEquals(List.of(".private", "automodpack-client.json", "automodpack-client.json.backup", "automodpack-content.json.temp", "automodpack-dummy-files.json", "automodpack-server.json.backup-2", "modpacks"),
				StorageLeftovers.leftovers(automodpack));
	}

	@Test
	void corruptAsideRenamesStaySilent() {
		assertFalse(StorageLeftovers.isLeftover("self-update.json.corrupt-1d2e3f"));
		assertFalse(StorageLeftovers.isLeftover("recovered"));
		assertTrue(StorageLeftovers.isLeftover("modpacks"));
	}

	@Test
	void missingDirectoryHasNoLeftovers(@TempDir Path empty) throws IOException {
		assertEquals(List.of(), StorageLeftovers.leftovers(empty.resolve("automodpack")));
	}
}
