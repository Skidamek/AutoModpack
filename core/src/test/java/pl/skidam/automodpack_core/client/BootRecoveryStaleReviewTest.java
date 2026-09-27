package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ReconfConfigs;
import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.PlannedAgainst;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Pins the boot contract for a pending update whose reviewed group selection no longer exists: the boot reverts, it never crash-loops. */
class BootRecoveryStaleReviewTest {

	@Test
	void aPendingUpdateWhoseReviewedGroupsChangedRevertsInsteadOfCrashingTheBoot(@TempDir Path temp) throws Exception {
		if (Constants.MODPACK_LOADER == null) {
			Constants.MODPACK_LOADER = new ModpackLoaderService() {
				@Override
				public void loadModpack(ModpackLoadRequest request) {}
			};
			Constants.LOADER = "fabric";
		}
		ClientStorage storage = TestDataRoot.open(temp.resolve("game"), temp.resolve("data"));
		GroupManifest manifest = TestPacks.manifest("test pack", "config/a.txt", "hello");
		String modpackId = manifest.modpackId();
		TestPacks.stageGeneration(storage, TestPacks.document(manifest));
		SelectionIntent selected = new SelectionIntent(Set.of("main"));
		new ClientSelectionStore(storage.selectionFile()).compareAndSet(modpackId, null, selected);
		SelectedModpackTarget target = SelectedModpackTarget.prepare(TestPacks.document(manifest), selected, selected, ClientPlatform.LINUX);
		String hash = HashUtils.sha1("hello".getBytes(StandardCharsets.UTF_8));
		UpdatePlan plan = new UpdatePlan(modpackId, target.packTarget(), List.of(
				new UpdatePlan.Operation(UpdatePlan.Root.PROJECTION, "config/a.txt", UpdatePlan.OperationType.INSTALL_OBJECT, hash, 5L, null)),
				List.of(new UpdatePlan.ProjectedFile(UpdatePlan.Root.PROJECTION, "config/a.txt", true, hash, 5L)),
				new ClientConfigJsons.ClientConfigFieldsV3(), modpackId, Set.of(UpdatePlan.RestartReason.SELECTED_MODPACK), List.of(), List.of(), List.of(), List.of(), ChangeSet.empty());
		ClientConfigJsons.ClientConfigFieldsV3 expectedConfig = ReconfConfigs.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
				.orElseGet(ClientConfigJsons.ClientConfigFieldsV3::new);
		ConfigTools.writeAtomic(storage.transactionFile(), UpdateTransaction.create(plan, target, storage.overlayDigest(modpackId), new PlannedAgainst(expectedConfig, "", null)));

		new ClientSelectionStore(storage.selectionFile()).remove(modpackId, selected);
		BootRecovery.BootDecision decision = new BootRecovery(storage).recover();

		assertTrue(decision.rolledBackStuckUpdate());
		assertFalse(Files.exists(storage.transactionFile()));
		try (Stream<Path> files = Files.list(storage.clientDirectory())) {
			assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("update-transaction.stuck-")));
		}
	}
}
