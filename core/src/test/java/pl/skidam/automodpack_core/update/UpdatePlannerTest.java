package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.update.UpdatePlan.*;

class UpdatePlannerTest {
	private static final String TARGET_HASH = "1111111111111111111111111111111111111111";
	private static final String OLD_HASH = "2222222222222222222222222222222222222222";
	private static final String OTHER_HASH = "3333333333333333333333333333333333333333";

	@Test
	void initialInstallRequiresRestart() {
		UpdatePlan plan = UpdatePlanner.plan(input(manifest(Map.of("mods/new.jar", item("mods/new.jar", TARGET_HASH, 9, "mod")),
				ledger(entry("mods/new.jar", TARGET_HASH, 9, OwnershipLedger.Status.PRESENT))), Map.of()));

		assertTrue(plan.restartReasons().contains(RestartReason.SELECTED_MODPACK));
	}

	@Test
	void modShapedFileOutsideModsIsInstalledAtItsDeclaredPath() {
		String path = "resourcepacks/mod-shaped-pack.jar";
		UpdatePlan plan = UpdatePlanner.plan(input(manifest(Map.of(path, item(path, TARGET_HASH, 9, "mod")),
				ledger(entry(path, TARGET_HASH, 9, OwnershipLedger.Status.PRESENT))), Map.of()));

		assertTrue(plan.operations().stream().anyMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals(path)
				&& operation.operation() == OperationType.INSTALL_OBJECT && TARGET_HASH.equals(operation.expectedObjectHash())));
		assertFalse(plan.restartReasons().contains(RestartReason.CORRECTED_FILE_LOCATIONS));
	}

	@Test
	void genericFileInsideModsIsInstalledAtItsDeclaredPath() {
		String path = "mods/README.txt";
		UpdatePlan plan = UpdatePlanner.plan(input(manifest(Map.of(path, item(path, TARGET_HASH, 9, "other")),
				ledger(entry(path, TARGET_HASH, 9, OwnershipLedger.Status.PRESENT))), Map.of()));

		assertTrue(plan.operations().stream().anyMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals(path)
				&& operation.operation() == OperationType.INSTALL_OBJECT && TARGET_HASH.equals(operation.expectedObjectHash())));
	}

	@Test
	void firstInstallConsentPreservesAndRemovesAnObservedLocalMod() {
		String path = "mods/local.jar";
		FileState local = new FileState(OLD_HASH, 8, true);
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, path), local);
		UpdatePlanner.Input input = new UpdatePlanner.Input(null, manifest(Map.of(), ledger()), files, Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3(), Map.of(path, local));

		UpdatePlan plan = UpdatePlanner.plan(input);

		assertEquals(List.of(new Preservation(Root.GAME_DIR, path, OLD_HASH, 8, PreservationProof.PLAYER_CONSENT)), plan.preservations());
		assertTrue(plan.operations().stream().anyMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals(path)
				&& operation.operation() == OperationType.DELETE && OLD_HASH.equals(operation.expectedExistingHash())));
		assertTrue(plan.restartReasons().contains(RestartReason.REMOVED_LOCAL_MODS));
	}

	@Test
	void firstInstallConsentPreservesBeforeReplacingTheSameLivePath() {
		String path = "mods/shared.jar";
		FileState local = new FileState(OLD_HASH, 8, true);
		ModpackJsons.ModpackContentFields target = manifest(Map.of(path, item(path, TARGET_HASH, 9, "other")),
				ledger(entry(path, TARGET_HASH, 9, OwnershipLedger.Status.PRESENT)));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, path), local);

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3(), Map.of(path, local)));

		Operation operation = plan.operations().stream().filter(value -> value.root() == Root.GAME_DIR && value.relativePath().equals(path)).findFirst().orElseThrow();
		assertEquals(OperationType.INSTALL_OBJECT, operation.operation());
		assertEquals(TARGET_HASH, operation.expectedObjectHash());
		assertEquals(OLD_HASH, operation.expectedExistingHash());
		assertEquals(PreservationProof.PLAYER_CONSENT, plan.preservations().get(0).proof());
	}

	@Test
	void firstInstallPreservesAnUnownedSameIdMod() {
		ModpackJsons.ModpackContentFields target = manifest(Map.of("mods/server.jar", item("mods/server.jar", TARGET_HASH, 9, "mod")),
				ledger(entry("mods/server.jar", TARGET_HASH, 9, OwnershipLedger.Status.PRESENT)));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.PROJECTION, "mods/server.jar"), new FileState(TARGET_HASH, 9, true),
				new FileKey(Root.GAME_DIR, "mods/local.jar"), new FileState(OLD_HASH, 8, true));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Map.of(), Set.of(),
				List.of(new ModInfo("mods/server.jar", TARGET_HASH, 9, Set.of("sodium"), Set.of())),
				List.of(new ModInfo("mods/local.jar", OLD_HASH, 8, Set.of("sodium"), Set.of())), List.of(), List.of(), null, new ClientConfigJsons.ClientConfigFieldsV3()));

		assertEquals(1, plan.conflicts().size());
		assertEquals(ConflictAction.PRESERVE_LOCAL, plan.conflicts().get(0).action());
		assertTrue(plan.operations().stream().anyMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("mods/local.jar")
				&& operation.operation() == OperationType.DELETE && OLD_HASH.equals(operation.expectedExistingHash())));
	}

	@Test
	void samePathSameContentStillGetsAnExplicitDisposition() {
		ModpackJsons.ModpackContentFields target = manifest(Map.of("mods/server.jar", item("mods/server.jar", TARGET_HASH, 9, "mod")),
				ledger(entry("mods/server.jar", TARGET_HASH, 9, OwnershipLedger.Status.PRESENT)));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.PROJECTION, "mods/server.jar"), new FileState(TARGET_HASH, 9, true),
				new FileKey(Root.GAME_DIR, "mods/server.jar"), new FileState(TARGET_HASH, 9, true));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Map.of(), Set.of(),
				List.of(new ModInfo("mods/server.jar", TARGET_HASH, 9, Set.of("sodium"), Set.of())),
				List.of(new ModInfo("mods/server.jar", TARGET_HASH, 9, Set.of("sodium"), Set.of())), List.of(), List.of(), null, new ClientConfigJsons.ClientConfigFieldsV3()));

		assertEquals(1, plan.conflicts().size());
		assertEquals(OperationType.DELETE, plan.operations().stream().filter(operation -> operation.root() == Root.GAME_DIR).findFirst().orElseThrow().operation());
	}

	@Test
	void switchingAtoBtoARetainsOnlyTheActivePackAndLocalMods() {
		String sharedA = "4444444444444444444444444444444444444444";
		String sharedB = "5555555555555555555555555555555555555555";
		String aOnly = "6666666666666666666666666666666666666666";
		String bOnly = "7777777777777777777777777777777777777777";
		String baseA = "8888888888888888888888888888888888888888";
		String baseB = "9999999999999999999999999999999999999999";
		String editedA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
		String local = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
		ModpackJsons.ModpackContentFields a = packManifest("packaa1", Map.of("mods/shared.jar", item("mods/shared.jar", sharedA, 1, "mod"),
				"mods/a.jar", item("mods/a.jar", aOnly, 1, "mod"), "config/shared.json", editableItem("config/shared.json", baseA, 1, "config")),
				entry("mods/shared.jar", sharedA, 1, OwnershipLedger.Status.PRESENT), entry("mods/a.jar", aOnly, 1, OwnershipLedger.Status.PRESENT),
				entry("config/shared.json", baseA, 1, OwnershipLedger.Status.PRESENT));
		ModpackJsons.ModpackContentFields b = packManifest("packbb1", Map.of("mods/shared.jar", item("mods/shared.jar", sharedB, 1, "mod"),
				"mods/b.jar", item("mods/b.jar", bOnly, 1, "mod"), "config/shared.json", editableItem("config/shared.json", baseB, 1, "config")),
				entryFor("packbb1", "mods/shared.jar", sharedB, 1), entryFor("packbb1", "mods/b.jar", bOnly, 1), entryFor("packbb1", "config/shared.json", baseB, 1));
		Map<FileKey, FileState> initial = Map.of(new FileKey(Root.GAME_DIR, "mods/local.jar"), new FileState(local, 1, true));
		Map<String, FileState> aOverlay = Map.of("config/shared.json", new FileState(editedA, 1, true));
		UpdatePlan first = UpdatePlanner.plan(new UpdatePlanner.Input(null, a, initial, aOverlay, Set.of(),
				List.of(mod("mods/shared.jar", sharedA, "shared"), mod("mods/a.jar", aOnly, "a")), List.of(mod("mods/local.jar", local, "local")), List.of(), List.of(), null,
				config("packaa1")));
		Map<FileKey, FileState> afterA = projectedFiles(first);
		Map<FileKey, FileState> beforeB = withoutOverlays(afterA);
		UpdatePlan second = UpdatePlanner.plan(new UpdatePlanner.Input(a, b, beforeB, Map.of(), Set.of(),
				List.of(mod("mods/shared.jar", sharedB, "shared"), mod("mods/b.jar", bOnly, "b")), List.of(mod("mods/local.jar", local, "local")), List.of(), List.of(),
				new UpdatePlanner.SelectionContext("packaa1", a, aOverlay), config("packbb1")));
		Map<FileKey, FileState> beforeAAgain = withoutOverlays(projectedFiles(second));
		UpdatePlan third = UpdatePlanner.plan(new UpdatePlanner.Input(b, a, beforeAAgain, aOverlay, Set.of(),
				List.of(mod("mods/shared.jar", sharedA, "shared"), mod("mods/a.jar", aOnly, "a")), List.of(mod("mods/local.jar", local, "local")), List.of(), List.of(),
				new UpdatePlanner.SelectionContext("packbb1", b, Map.of()), config("packaa1")));

		assertTrue(second.operations().stream().anyMatch(operation -> operation.root() == Root.PROJECTION && operation.relativePath().equals("mods/a.jar")
				&& operation.operation() == OperationType.DELETE));
		assertFalse(second.operations().stream().anyMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("mods/local.jar")));
		assertTrue(second.projectedFinalState().stream().anyMatch(file -> file.root() == Root.PROJECTION && file.relativePath().equals("mods/b.jar") && file.present()));
		assertFalse(third.projectedFinalState().stream().anyMatch(file -> file.root() == Root.PROJECTION && file.relativePath().equals("mods/b.jar") && file.present()));
		assertTrue(third.operations().stream().anyMatch(operation -> operation.root() == Root.OVERLAY && operation.relativePath().equals("config/shared.json")
				&& operation.operation() == OperationType.INSTALL_OBJECT && editedA.equals(operation.expectedObjectHash())));
		assertTrue(third.projectedFinalState().stream().anyMatch(file -> file.root() == Root.GAME_DIR && file.relativePath().equals("mods/local.jar") && file.present()));
	}

	@Test
	void cleanupUsesHistoricalHashAndSizeForManagedFiles() {
		ModpackJsons.ModpackContentFields target = manifest(Map.of(
				"mods/new.jar", item("mods/new.jar", TARGET_HASH, 9, "mod"),
				"config/kept.json", item("config/kept.json", OTHER_HASH, 4, "config")),
				ledger(entry("mods/old.jar", OLD_HASH, 8, OwnershipLedger.Status.TOMBSTONE),
						entry("mods/new.jar", TARGET_HASH, 9, OwnershipLedger.Status.PRESENT),
						entry("config/kept.json", OTHER_HASH, 4, OwnershipLedger.Status.PRESENT)));
		Map<FileKey, FileState> files = new LinkedHashMap<>();
		files.put(new FileKey(Root.GAME_DIR, "mods/old.jar"), new FileState(OLD_HASH, 8, true));
		files.put(new FileKey(Root.PROJECTION, "mods/new.jar"), new FileState(TARGET_HASH, 9, true));
		files.put(new FileKey(Root.GAME_DIR, "config/kept.json"), new FileState(OTHER_HASH, 4, true));

		ModpackJsons.ModpackContentFields installed = manifest(Map.of("mods/old.jar", item("mods/old.jar", OLD_HASH, 8, "mod")),
				ledger(entry("mods/old.jar", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, target, files, Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3()));

		assertTrue(plan.operations().stream().anyMatch(operation -> operation.root() == Root.GAME_DIR
				&& operation.relativePath().equals("mods/old.jar") && operation.operation() == OperationType.DELETE
				&& OLD_HASH.equals(operation.expectedExistingHash())));
		assertTrue(plan.restartReasons().contains(RestartReason.APPLIED_SERVER_DELETIONS));
		assertEquals(List.of(new Preservation(Root.GAME_DIR, "mods/old.jar", OLD_HASH, 8)), plan.preservations());
		assertEquals(List.of(new BaselineCapture(Root.GAME_DIR, "mods/old.jar", OLD_HASH, 8, false)), plan.baselineCaptures());
	}

	@Test
	void generatedCopiesAreRemovedOnlyWhenTheirOwnedBytesStillMatch() {
		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.GAME_DIR, "mods/nested-old.jar"), new FileState(OLD_HASH, 8, true),
				new FileKey(Root.GAME_DIR, "mods/nested-edited.jar"), new FileState(OTHER_HASH, 8, true),
				new FileKey(Root.GAME_DIR, "mods/local.jar"), new FileState(TARGET_HASH, 9, true));
		List<NestedCopy> previous = List.of(new NestedCopy("mods/nested-old.jar", OLD_HASH, 8, Set.of("nested-old")),
				new NestedCopy("mods/nested-edited.jar", OLD_HASH, 8, Set.of("nested-edited")));

		UpdatePlan plan = planWithGeneratedCopies(manifest(Map.of(), ledger()), files, previous, List.of());

		assertTrue(plan.operations().stream().anyMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("mods/nested-old.jar")
				&& operation.operation() == OperationType.DELETE && OLD_HASH.equals(operation.expectedExistingHash())));
		assertTrue(plan.operations().stream().noneMatch(operation -> operation.relativePath().equals("mods/nested-edited.jar")));
		assertTrue(plan.operations().stream().noneMatch(operation -> operation.relativePath().equals("mods/local.jar")));
	}

	@Test
	void generatedCopyReplacementIsPinnedToThePreviouslyOwnedBytes() {
		List<NestedCopy> previous = List.of(new NestedCopy("mods/nested.jar", OLD_HASH, 8, Set.of("nested")));
		List<NestedCopy> targetCopies = List.of(new NestedCopy("mods/nested.jar", TARGET_HASH, 9, Set.of("nested")));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "mods/nested.jar"), new FileState(OLD_HASH, 8, true));

		UpdatePlan plan = planWithGeneratedCopies(manifest(Map.of(), ledger()), files, previous, targetCopies);

		Operation operation = plan.operations().stream().filter(value -> value.root() == Root.GAME_DIR && value.relativePath().equals("mods/nested.jar")).findFirst().orElseThrow();
		assertEquals(OperationType.INSTALL_OBJECT, operation.operation());
		assertEquals(TARGET_HASH, operation.expectedObjectHash());
		assertEquals(OLD_HASH, operation.expectedExistingHash());
	}

	@Test
	void generatedCopyDoesNotOverwriteAnUnownedLocalFile() {
		List<NestedCopy> targetCopies = List.of(new NestedCopy("mods/nested.jar", TARGET_HASH, 9, Set.of("nested")));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "mods/nested.jar"), new FileState(OTHER_HASH, 8, true));

		UpdatePlan plan = planWithGeneratedCopies(manifest(Map.of(), ledger()), files, List.of(), targetCopies);

		assertTrue(plan.operations().stream().noneMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("mods/nested.jar")));
	}

	@Test
	void removalCleansOnlyUnmodifiedGeneratedCopies() {
		ModpackJsons.ModpackContentFields installed = manifest(Map.of("mods/root.jar", item("mods/root.jar", TARGET_HASH, 9, "mod")),
				ledger(entry("mods/root.jar", TARGET_HASH, 9, OwnershipLedger.Status.PRESENT)));
		ClientStorageJsons.ClientBaselineFields baseline = new ClientStorageJsons.ClientBaselineFields();
		baseline.modpackId = installed.modpackId;
		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.PROJECTION, "mods/root.jar"), new FileState(TARGET_HASH, 9, true),
				new FileKey(Root.GAME_DIR, "mods/root.jar"), new FileState(TARGET_HASH, 9, true),
				new FileKey(Root.GAME_DIR, "mods/nested.jar"), new FileState(OLD_HASH, 8, true),
				new FileKey(Root.GAME_DIR, "mods/nested-edited.jar"), new FileState(OTHER_HASH, 8, true),
				new FileKey(Root.GAME_DIR, "mods/local.jar"), new FileState(TARGET_HASH, 9, true));
		GeneratedCopyState generated = new GeneratedCopyState(installed.modpackId, installed.targetGenerationId, "3".repeat(40), List.of(
				new GeneratedCopyState.Entry("mods/nested.jar", OLD_HASH, 8), new GeneratedCopyState.Entry("mods/nested-edited.jar", OLD_HASH, 8)));

		UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, baseline, files, Set.of(), generated, new ClientConfigJsons.ClientConfigFieldsV3()));

		assertTrue(plan.operations().stream().anyMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("mods/nested.jar")
				&& operation.operation() == OperationType.DELETE && OLD_HASH.equals(operation.expectedExistingHash())));
		assertTrue(plan.operations().stream().noneMatch(operation -> operation.relativePath().equals("mods/nested-edited.jar") || operation.relativePath().equals("mods/local.jar")));
		assertTrue(plan.projectedFinalState().stream().noneMatch(file -> file.root() == Root.GAME_DIR && file.relativePath().equals("mods/nested.jar") && file.present()));
		assertTrue(plan.projectedFinalState().stream().anyMatch(file -> file.root() == Root.GAME_DIR && file.relativePath().equals("mods/nested-edited.jar") && file.present()));
		assertTrue(plan.restartReasons().contains(RestartReason.SELECTED_MODPACK));
	}

	@Test
	void removalDeletesExactInstalledLiveCopyWhenBaselineEntryIsMissing() {
		String path = "test/server-owned.mp4";
		ModpackJsons.ModpackContentFields installed = manifest(Map.of(path, item(path, TARGET_HASH, 9, "other")),
				ledger(entry(path, TARGET_HASH, 9, OwnershipLedger.Status.PRESENT)));
		ClientStorageJsons.ClientBaselineFields baseline = new ClientStorageJsons.ClientBaselineFields();
		baseline.modpackId = installed.modpackId;
		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.PROJECTION, path), new FileState(TARGET_HASH, 9, true),
				new FileKey(Root.GAME_DIR, path), new FileState(TARGET_HASH, 9, true));

		UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, baseline, files, Set.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3()));

		assertTrue(plan.operations().stream().anyMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals(path)
				&& operation.operation() == OperationType.DELETE && TARGET_HASH.equals(operation.expectedExistingHash())));
	}

	@Test
	void cleanupPreservesMismatchesUnsafeTypesAndPlayerLocalPaths() {
		String localHash = "4444444444444444444444444444444444444444";
		ModpackJsons.ModpackContentFields target = manifest(Map.of(), ledger(
				entry("config/changed.json", OLD_HASH, 8, OwnershipLedger.Status.TOMBSTONE),
				entry("config/size.json", OLD_HASH, 8, OwnershipLedger.Status.TOMBSTONE),
				entry("config/link.json", OLD_HASH, 8, OwnershipLedger.Status.TOMBSTONE),
				entry("saves/world.dat", localHash, 8, OwnershipLedger.Status.TOMBSTONE)));
		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.GAME_DIR, "config/changed.json"), new FileState(OTHER_HASH, 8, true),
				new FileKey(Root.GAME_DIR, "config/size.json"), new FileState(OLD_HASH, 9, true),
				new FileKey(Root.GAME_DIR, "config/link.json"), new FileState(OLD_HASH, 8, false),
				new FileKey(Root.GAME_DIR, "saves/world.dat"), new FileState(localHash, 8, true));

		UpdatePlan plan = UpdatePlanner.plan(input(target, files));

		assertTrue(plan.operations().stream().noneMatch(operation -> operation.operation() == OperationType.DELETE));
		assertTrue(UpdatePlanner.managedCleanupKey("config/changed.json").isPresent());
		assertTrue(UpdatePlanner.managedCleanupKey("config/size.json").isPresent());
		assertTrue(UpdatePlanner.managedCleanupKey("saves/world.dat").isEmpty());
		assertEquals(Optional.of(new FileKey(Root.GAME_DIR, "mods")), UpdatePlanner.managedCleanupKey("mods"));
		assertEquals(Optional.of(new FileKey(Root.GAME_DIR, "mods/nested/old.jar")), UpdatePlanner.managedCleanupKey("mods/nested/old.jar"));
	}

	@Test
	void installedPackDoesNotDeleteAnEditedReplacement() {
		ModpackJsons.ModpackContentFields installed = manifest(Map.of("mods/sodium.jar", item("mods/sodium.jar", OLD_HASH, 8, "mod")),
				ledger(entry("mods/sodium.jar", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		ModpackJsons.ModpackContentFields target = manifest(Map.of(), ledger(entry("mods/sodium.jar", OLD_HASH, 8, OwnershipLedger.Status.TOMBSTONE)));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "mods/sodium.jar"), new FileState(OTHER_HASH, 8, true));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, target, files, Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3()));

		assertTrue(plan.operations().stream().noneMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("mods/sodium.jar")
				&& operation.operation() == OperationType.DELETE));
		assertTrue(plan.projectedFinalState().stream().anyMatch(file -> file.root() == Root.GAME_DIR && file.relativePath().equals("mods/sodium.jar") && file.present()));
	}

	@Test
	void disabledGroupFileIsOutsideClientCleanupScope() {
		ModpackJsons.ModpackContentFields installed = manifest(Map.of(), ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		ModpackJsons.ModpackContentFields target = manifest(Map.of(), ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "config/connector.json"), new FileState(OLD_HASH, 8, true));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, target, files, Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3()));

		assertTrue(plan.operations().stream().noneMatch(operation -> operation.relativePath().equals("config/connector.json")));
		assertTrue(plan.preservations().isEmpty());
	}

	@Test
	void deselectedGroupFileAlreadyMatchingBaselineIsLeftAlone() {
		ModpackJsons.ModpackContentFields installed = manifest(Map.of("config/connector.json", item("config/connector.json", OLD_HASH, 8, "config")),
				ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		ModpackJsons.ModpackContentFields target = manifest(Map.of(), ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		ClientStorageJsons.ClientBaselineFields baseline = new ClientStorageJsons.ClientBaselineFields();
		baseline.modpackId = installed.modpackId;
		ClientStorageJsons.ClientBaselineFields.EntryFields baselineEntry = new ClientStorageJsons.ClientBaselineFields.EntryFields();
		baselineEntry.logicalPath = "config/connector.json";
		baselineEntry.objectHash = OLD_HASH;
		baselineEntry.size = 8;
		baseline.entries = List.of(baselineEntry);
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "config/connector.json"), new FileState(OLD_HASH, 8, true));
		UpdatePlanner.SelectionContext selection = new UpdatePlanner.SelectionContext(installed.modpackId, installed, Map.of(), baseline, Set.of(OLD_HASH));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, target, files, Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), selection,
				new ClientConfigJsons.ClientConfigFieldsV3()));

		assertTrue(plan.operations().stream().noneMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("config/connector.json")));
		assertTrue(plan.preservations().isEmpty());
	}

	@Test
	void deselectedGroupFileRestoredFromBaselineIsNotAlsoPreserved() {
		ModpackJsons.ModpackContentFields installed = manifest(Map.of("config/connector.json", item("config/connector.json", OLD_HASH, 8, "config")),
				ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		ModpackJsons.ModpackContentFields target = manifest(Map.of(), ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.TOMBSTONE)));
		String baselineHash = "4444444444444444444444444444444444444444";
		ClientStorageJsons.ClientBaselineFields baseline = new ClientStorageJsons.ClientBaselineFields();
		baseline.modpackId = installed.modpackId;
		ClientStorageJsons.ClientBaselineFields.EntryFields baselineEntry = new ClientStorageJsons.ClientBaselineFields.EntryFields();
		baselineEntry.logicalPath = "config/connector.json";
		baselineEntry.objectHash = baselineHash;
		baselineEntry.size = 8;
		baseline.entries = List.of(baselineEntry);
		UpdatePlanner.SelectionContext selection = new UpdatePlanner.SelectionContext(installed.modpackId, installed, Map.of(), baseline, Set.of(baselineHash));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, target,
				Map.of(new FileKey(Root.GAME_DIR, "config/connector.json"), new FileState(OLD_HASH, 8, true)), Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), selection,
				new ClientConfigJsons.ClientConfigFieldsV3()));

		assertEquals(List.of(), plan.preservations());
		assertEquals(baselineHash, plan.operations().stream().filter(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("config/connector.json"))
				.findFirst().orElseThrow().expectedObjectHash());
	}

	@Test
	void freshClientMayRemoveOnlyExactServerKnownTombstoneBytes() {
		ModpackJsons.ModpackContentFields target = manifest(Map.of(), ledger(entry("mods/removed.jar", OLD_HASH, 8, OwnershipLedger.Status.TOMBSTONE)));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "mods/removed.jar"), new FileState(OLD_HASH, 8, true),
				new FileKey(Root.GAME_DIR, "mods/unrelated.jar"), new FileState(OTHER_HASH, 8, true));

		UpdatePlan plan = UpdatePlanner.plan(input(target, files));

		assertTrue(plan.operations().stream().anyMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("mods/removed.jar")
				&& operation.operation() == OperationType.DELETE));
		assertEquals(PreservationProof.SERVER_LEDGER, plan.preservations().get(0).proof());
		assertTrue(plan.operations().stream().noneMatch(operation -> operation.relativePath().equals("mods/unrelated.jar")));
	}

	@Test
	void editedEditableFilesBecomeLineageOverlays() {
		String editedOldHash = "5555555555555555555555555555555555555555";
		String editedTargetHash = "6666666666666666666666666666666666666666";
		ModpackJsons.ModpackContentFields target = new ModpackJsons.ModpackContentFields(Set.of(
				editableItem("config/settings.json", TARGET_HASH, 7, "config")));
		target.modpackId = "abc1234";
		target.targetGenerationId = "1".repeat(40);
		target.parentGenerationId = "";
		target.stateDigest = "2".repeat(40);
		target.ownershipLedger = ledger(entry("config/settings.json", TARGET_HASH, 7, OwnershipLedger.Status.PRESENT));
		ModpackJsons.ModpackContentFields previous = new ModpackJsons.ModpackContentFields(Set.of(
				editableItem("config/settings.json", OLD_HASH, 6, "config")));
		previous.modpackId = "old1234";
		previous.targetGenerationId = "3".repeat(40);
		previous.parentGenerationId = "";
		previous.stateDigest = "4".repeat(40);
		previous.ownershipLedger = ledgerFor("old1234", entry("config/settings.json", OLD_HASH, 6, OwnershipLedger.Status.PRESENT));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "config/settings.json"), new FileState(editedTargetHash, 7, true),
				new FileKey(Root.PROJECTION, "config/settings.json"), new FileState(editedTargetHash, 7, true));
		UpdatePlanner.Input input = new UpdatePlanner.Input(null, target, files,
				Map.of("config/settings.json", new FileState(editedOldHash, 6, true)), Set.of(), List.of(), List.of(), List.of(), List.of(),
				new UpdatePlanner.SelectionContext("old1234", previous), new ClientConfigJsons.ClientConfigFieldsV3());

		UpdatePlan plan = UpdatePlanner.plan(input);

		assertTrue(plan.operations().stream().anyMatch(operation -> operation.root() == Root.OVERLAY
				&& operation.relativePath().equals("config/settings.json") && operation.expectedObjectHash().equals(editedOldHash)));
		assertTrue(plan.operations().stream().anyMatch(operation -> operation.root() == Root.GAME_DIR
				&& operation.relativePath().equals("config/settings.json") && operation.expectedObjectHash().equals(editedOldHash)));
		assertTrue(plan.restartReasons().contains(RestartReason.SELECTED_MODPACK));
	}

	private static UpdatePlanner.Input input(ModpackJsons.ModpackContentFields target, Map<FileKey, FileState> files) {
		return new UpdatePlanner.Input(null, target, files, Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null, new ClientConfigJsons.ClientConfigFieldsV3());
	}

	private static UpdatePlan planWithGeneratedCopies(ModpackJsons.ModpackContentFields target, Map<FileKey, FileState> files, List<NestedCopy> previous, List<NestedCopy> generated) {
		return UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Map.of(), Set.of(), List.of(), List.of(), previous, generated, null,
				new ClientConfigJsons.ClientConfigFieldsV3()));
	}

	private static ModpackJsons.ModpackContentFields manifest(Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> items,
			GenerationJsons.OwnershipLedgerFields ownershipLedger) {
		ModpackJsons.ModpackContentFields target = new ModpackJsons.ModpackContentFields(new LinkedHashSet<>(items.values()));
		target.modpackId = "abc1234";
		target.targetGenerationId = "1".repeat(40);
		target.parentGenerationId = "";
		target.stateDigest = "2".repeat(40);
		target.ownershipLedger = ownershipLedger;
		return target;
	}

	private static ModpackJsons.ModpackContentFields.ModpackContentItem item(String path, String hash, long size, String type) {
		return new ModpackJsons.ModpackContentFields.ModpackContentItem(path, String.valueOf(size), type, false, false, hash, "0");
	}

	private static ModpackJsons.ModpackContentFields.ModpackContentItem editableItem(String path, String hash, long size, String type) {
		return new ModpackJsons.ModpackContentFields.ModpackContentItem(path, String.valueOf(size), type, true, false, hash, "0");
	}

	private static ModpackJsons.ModpackContentFields packManifest(String modpackId, Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> items,
			OwnershipLedger.Entry... entries) {
		ModpackJsons.ModpackContentFields target = new ModpackJsons.ModpackContentFields(new LinkedHashSet<>(items.values()));
		target.modpackId = modpackId;
		target.targetGenerationId = "1".repeat(40);
		target.parentGenerationId = "";
		target.stateDigest = "2".repeat(40);
		target.ownershipLedger = ledgerFor(modpackId, entries);
		return target;
	}

	private static OwnershipLedger.Entry entryFor(String modpackId, String path, String hash, long size) {
		return new OwnershipLedger.Entry(path, Set.of(new OwnershipLedger.Content(hash, size)), Set.of("main"), "a".repeat(40), "b".repeat(40), OwnershipLedger.Status.PRESENT);
	}

	private static ModInfo mod(String path, String hash, String id) {
		return new ModInfo(path, hash, 1, Set.of(id), Set.of());
	}

	private static ClientConfigJsons.ClientConfigFieldsV3 config(String modpackId) {
		ClientConfigJsons.ClientConfigFieldsV3 config = new ClientConfigJsons.ClientConfigFieldsV3();
		config.selectedModpackId = modpackId;
		return config;
	}

	private static Map<FileKey, FileState> projectedFiles(UpdatePlan plan) {
		Map<FileKey, FileState> files = new LinkedHashMap<>();
		for (ProjectedFile file : plan.projectedFinalState())
			if (file.present()) files.put(new FileKey(file.root(), file.relativePath()), new FileState(file.expectedHash(), file.expectedSize(), true));
		return files;
	}

	private static Map<FileKey, FileState> withoutOverlays(Map<FileKey, FileState> files) {
		return files.entrySet().stream().filter(entry -> entry.getKey().root() != Root.OVERLAY)
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first, LinkedHashMap::new));
	}

	private static GenerationJsons.OwnershipLedgerFields ledger(OwnershipLedger.Entry... entries) {
		return ledgerFor("abc1234", entries);
	}

	private static GenerationJsons.OwnershipLedgerFields ledgerFor(String modpackId, OwnershipLedger.Entry... entries) {
		Map<String, OwnershipLedger.Entry> values = new TreeMap<>();
		for (OwnershipLedger.Entry entry : entries) values.put(entry.logicalPath(), entry);
		return new OwnershipLedger(modpackId, values).toFields();
	}

	private static OwnershipLedger.Entry entry(String path, String hash, long size, OwnershipLedger.Status status) {
		return new OwnershipLedger.Entry(path, Set.of(new OwnershipLedger.Content(hash, size)), Set.of("main"), "a".repeat(40), "b".repeat(40), status);
	}
}
