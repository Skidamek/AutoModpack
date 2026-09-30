package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.client.ModpackUtils;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;

/** The join verdict reads the committed projection, not the game-dir copies a running mod may rewrite at will. */
class ModpackUpdateCheckTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void gameDirectoryDriftOfACommittedPackFileIsNotAnUpdatePrompt() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		byte[] bytes = "pack-owned-cache".getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(bytes);
		ClientObjectStore.storeObject(storage, hash, bytes);
		SelectedModpackTarget target = UpdateTestFixtures.target(storage, "config/durw/cache.db", "config", false, hash, bytes.length);
		UpdatePlan plan = UpdateTestFixtures.plan(target, new ClientConfigJsons.ClientConfigFieldsV3(), List.of(
				new Operation(Root.PROJECTION, "config/durw/cache.db", OperationType.INSTALL_OBJECT, hash, bytes.length, null),
				new Operation(Root.GAME_DIR, "config/durw/cache.db", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "config/durw/cache.db", true, hash, bytes.length),
						new ProjectedFile(Root.GAME_DIR, "config/durw/cache.db", true, hash, bytes.length)));
		assertTrue(UpdateTestFixtures.commit(storage, plan, target).success());
		ModpackJsons.ModpackContentFields serverContent = target.flatTarget();

		// A running mod rewrites the game-dir copy between sessions; the join verdict must not see it as an update.
		Path gameCopy = storage.gameDirectory().resolve("config/durw/cache.db");
		Files.write(gameCopy, "rewritten-by-the-running-mod".getBytes(StandardCharsets.UTF_8));
		assertFalse(ModpackUtils.isUpdate(serverContent, storage).requiresUpdate());

		// Tampering with the committed projection itself is still a repair-worthy update.
		Path activeCopy = storage.activePath("config/durw/cache.db");
		Files.delete(activeCopy);
		Files.write(activeCopy, "tampered".getBytes(StandardCharsets.UTF_8));
		assertTrue(ModpackUtils.isUpdate(serverContent, storage).requiresUpdate());
	}
}
