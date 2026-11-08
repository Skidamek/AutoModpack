package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.storage.TestDataRoot;

class ClientStorageProjectionGateTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void aMissingPointerIsNotAMismatch() {
		assertNull(ClientStorage.activeSelectionMismatch((String) null, null));
		assertNull(ClientStorage.activeSelectionMismatch("", null));
	}

	@Test
	void aHalfPointerIsAMismatch() {
		ClientStorageJsons.ClientGenerationStateFields state = new ClientStorageJsons.ClientGenerationStateFields();
		state.modpackId = "abc1234";
		assertNotNull(ClientStorage.activeSelectionMismatch("", state));
		assertNotNull(ClientStorage.activeSelectionMismatch("abc1234", null));
		ClientStorageJsons.ClientGenerationStateFields other = new ClientStorageJsons.ClientGenerationStateFields();
		other.modpackId = "def5678";
		assertNotNull(ClientStorage.activeSelectionMismatch("abc1234", other));
	}

	@Test
	void scanTimeHandsTheModsDirectoryWhenNoPointerExists() throws Exception {
		ClientStorage storage = storage();
		Path mods = storage.activePath("mods");
		Files.createDirectories(mods);
		Files.writeString(mods.resolve("restored.jar"), "jar", StandardCharsets.UTF_8);

		assertEquals(mods.toAbsolutePath().normalize(), ProjectionAvailability.loadableModsDirectory(storage.gameDirectory()).toAbsolutePath().normalize());
	}

	@Test
	void scanTimeHidesTheModsDirectoryWhenOnlyActiveStateExists() throws Exception {
		ClientStorage storage = storage();
		Path mods = storage.activePath("mods");
		Files.createDirectories(mods);
		Files.writeString(mods.resolve("restored.jar"), "jar", StandardCharsets.UTF_8);
		storage.writeActiveState(TestPacks.document(TestPacks.manifest("pack", "config/pack.txt", "pack-file")));

		assertNull(ProjectionAvailability.loadableModsDirectory(storage.gameDirectory()));
	}

	@Test
	void scanTimeRefusesAConvergingProjectionAndRecordsTheRevisionItDecidedOn() throws Exception {
		ClientStorage storage = storage();
		Path mods = storage.activePath("mods");
		Files.createDirectories(mods);
		Files.writeString(mods.resolve("half.jar"), "jar", StandardCharsets.UTF_8);
		ProjectionState.converging(storage, "tx", List.of());

		assertNull(ProjectionAvailability.loadableModsDirectory(storage.gameDirectory()));
		assertEquals(1L, ProjectionAvailability.scan(storage.gameDirectory()).revision());
		assertNotNull(ProjectionState.loadRefusal(storage.gameDirectory()));

		ProjectionState.settled(storage, "tx");
		assertNull(ProjectionState.loadRefusal(storage.gameDirectory()));
		assertNotNull(ProjectionAvailability.loadableModsDirectory(storage.gameDirectory()));
		assertEquals(2L, ProjectionAvailability.scan(storage.gameDirectory()).revision());
	}

	@Test
	void anUnreadableProjectionStateRefusesEveryGate() throws Exception {
		ClientStorage storage = storage();
		Files.createDirectories(storage.activePath("mods"));
		Files.writeString(storage.projectionStateFile(), "{not json", StandardCharsets.UTF_8);

		assertNotNull(ProjectionState.loadRefusal(storage.gameDirectory()));
		assertNull(ProjectionAvailability.loadableModsDirectory(storage.gameDirectory()));
		assertEquals(-1L, ProjectionAvailability.scan(storage.gameDirectory()).revision());
	}

	@Test
	void theScanDecisionLivesForTheProcessUntilForget() throws Exception {
		ClientStorage storage = storage();
		Files.createDirectories(storage.activePath("mods"));
		assertNotNull(ProjectionAvailability.loadableModsDirectory(storage.gameDirectory()));
		ProjectionAvailability.Scan scan = ProjectionAvailability.scan(storage.gameDirectory());
		assertNotNull(scan);
		assertSame(scan, ProjectionAvailability.scan(storage.gameDirectory()));
		ProjectionAvailability.forget(storage.gameDirectory());
		assertNull(ProjectionAvailability.scan(storage.gameDirectory()));
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}
}
