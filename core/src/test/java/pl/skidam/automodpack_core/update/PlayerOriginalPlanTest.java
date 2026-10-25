package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.skidam.automodpack_core.update.UpdatePlannerTest.OLD_HASH;
import static pl.skidam.automodpack_core.update.UpdatePlannerTest.TARGET_HASH;
import static pl.skidam.automodpack_core.update.UpdatePlannerTest.entry;
import static pl.skidam.automodpack_core.update.UpdatePlannerTest.item;
import static pl.skidam.automodpack_core.update.UpdatePlannerTest.ledger;
import static pl.skidam.automodpack_core.update.UpdatePlannerTest.manifest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.update.UpdatePlan.*;

/** When the planner gives the player's own file back: only on leaving a path, never when the pack drops it, and never over the only copy of their work. */
class PlayerOriginalPlanTest {
	/**
	 * The player's original is known but its bytes are gone from the store: removal cannot give it back, so it must not
	 * delete the player's edited copy either - the same rule a pack-owned file follows when its baseline object is missing.
	 */
	@Test
	void removalKeepsAnEditedEditableCopyWhenThePlayersOriginalCannotBeRestored() {
		String path = "config/editable.toml";
		String edited = "5555555555555555555555555555555555555555";
		String player = "4444444444444444444444444444444444444444";
		ModpackJsons.ModpackContentFields installed = manifest(Map.of(path, new ModpackJsons.ModpackContentFields.ModpackContentItem(path, 9, true, TARGET_HASH, "0")),
				ledger(entry(path, TARGET_HASH, 9, OwnershipLedger.Status.PRESENT)));
		Map<String, InstanceTree.TrackedFile> baseline = Map.of(path, new InstanceTree.TrackedFile(Root.GAME_DIR, "", path, player, 6));
		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.PROJECTION, path), new FileState(TARGET_HASH, 9, true),
				new FileKey(Root.OVERLAY, path), new FileState(edited, 6, true),
				new FileKey(Root.GAME_DIR, path), new FileState(edited, 6, true));

		UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, baseline, files, Set.of(), null, new ClientConfigJsons.ClientConfigFieldsV3()));

		assertEquals(List.of(), plan.operations().stream().filter(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals(path)).toList(),
				"the original's object is unavailable, so the edited copy is the only copy of the player's work left");
	}

	@Test
	void deselectedGroupFileAlreadyMatchingBaselineIsLeftAlone() {
		ModpackJsons.ModpackContentFields installed = manifest(Map.of("config/connector.json", item("config/connector.json", OLD_HASH, 8, "config")),
				ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		installed.selectedGroups = Set.of("connector");
		ModpackJsons.ModpackContentFields target = manifest(Map.of(), ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		Map<String, InstanceTree.TrackedFile> baseline = Map.of("config/connector.json", new InstanceTree.TrackedFile(Root.GAME_DIR, "", "config/connector.json", OLD_HASH, 8));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "config/connector.json"), new FileState(OLD_HASH, 8, true));
		UpdatePlanner.SelectionContext selection = new UpdatePlanner.SelectionContext(installed.modpackId, installed, Map.of(), baseline, Set.of(OLD_HASH), Set.of("config/connector.json"));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, target, files, Set.of(), List.of(), List.of(), List.of(), List.of(), selection,
				new ClientConfigJsons.ClientConfigFieldsV3()));

		assertTrue(plan.operations().stream().noneMatch(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("config/connector.json")));
		assertTrue(plan.preservations().isEmpty());
	}

	@Test
	void deselectedGroupFileRestoredFromBaselineIsNotAlsoPreserved() {
		ModpackJsons.ModpackContentFields installed = manifest(Map.of("config/connector.json", item("config/connector.json", OLD_HASH, 8, "config")),
				ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		installed.selectedGroups = Set.of("connector");
		ModpackJsons.ModpackContentFields target = manifest(Map.of(), ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		String baselineHash = "4444444444444444444444444444444444444444";
		Map<String, InstanceTree.TrackedFile> baseline = Map.of("config/connector.json", new InstanceTree.TrackedFile(Root.GAME_DIR, "", "config/connector.json", baselineHash, 8));
		UpdatePlanner.SelectionContext selection = new UpdatePlanner.SelectionContext(installed.modpackId, installed, Map.of(), baseline, Set.of(baselineHash), Set.of("config/connector.json"));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, target,
				Map.of(new FileKey(Root.GAME_DIR, "config/connector.json"), new FileState(OLD_HASH, 8, true)), Set.of(), List.of(), List.of(), List.of(), List.of(), selection,
				new ClientConfigJsons.ClientConfigFieldsV3()));

		assertEquals(List.of(), plan.preservations());
		assertEquals(baselineHash, plan.operations().stream().filter(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("config/connector.json"))
				.findFirst().orElseThrow().expectedObjectHash());
	}

	@Test
	void fileDroppedByAnUpdateOfTheSamePackAndSelectionIsDeletedNotGivenBack() {
		ModpackJsons.ModpackContentFields installed = manifest(Map.of("config/connector.json", item("config/connector.json", OLD_HASH, 8, "config")),
				ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		ModpackJsons.ModpackContentFields target = manifest(Map.of(), ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.TOMBSTONE)));
		String baselineHash = "4444444444444444444444444444444444444444";
		Map<String, InstanceTree.TrackedFile> baseline = Map.of("config/connector.json", new InstanceTree.TrackedFile(Root.GAME_DIR, "", "config/connector.json", baselineHash, 8));
		UpdatePlanner.SelectionContext selection = new UpdatePlanner.SelectionContext(installed.modpackId, installed, Map.of(), baseline, Set.of(baselineHash), Set.of());

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, target,
				Map.of(new FileKey(Root.GAME_DIR, "config/connector.json"), new FileState(OLD_HASH, 8, true)), Set.of(), List.of(), List.of(), List.of(), List.of(), selection,
				new ClientConfigJsons.ClientConfigFieldsV3()));

		assertEquals(OperationType.DELETE, plan.operations().stream().filter(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("config/connector.json"))
				.findFirst().orElseThrow().operation());
		assertEquals(PreservationProof.ACTIVE_LEDGER, plan.preservations().get(0).proof());
	}

	@Test
	void fileAnUpdateDropsIsDeletedEvenWhenTheSameUpdateAlsoAddsAGroup() {
		ModpackJsons.ModpackContentFields installed = manifest(Map.of("config/connector.json", item("config/connector.json", OLD_HASH, 8, "config")),
				ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		installed.selectedGroups = Set.of("main");
		ModpackJsons.ModpackContentFields target = manifest(Map.of(), ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.TOMBSTONE)));
		target.selectedGroups = Set.of("main", "extras");
		String baselineHash = "4444444444444444444444444444444444444444";
		Map<String, InstanceTree.TrackedFile> baseline = Map.of("config/connector.json", new InstanceTree.TrackedFile(Root.GAME_DIR, "", "config/connector.json", baselineHash, 8));
		UpdatePlanner.SelectionContext selection = new UpdatePlanner.SelectionContext(installed.modpackId, installed, Map.of(), baseline, Set.of(baselineHash), Set.of());

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, target,
				Map.of(new FileKey(Root.GAME_DIR, "config/connector.json"), new FileState(OLD_HASH, 8, true)), Set.of(), List.of(), List.of(), List.of(), List.of(), selection,
				new ClientConfigJsons.ClientConfigFieldsV3()));

		assertEquals(OperationType.DELETE, plan.operations().stream().filter(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("config/connector.json"))
				.findFirst().orElseThrow().operation());
	}

	/** The server moves a path from main into an optional group the player never picked; the player left nothing, so the update just drops it. */
	@Test
	void aPathAnUpdateMovesIntoAnUnselectedGroupIsDroppedNotGivenBack() {
		ModpackJsons.ModpackContentFields installed = manifest(Map.of("config/connector.json", item("config/connector.json", OLD_HASH, 8, "config")),
				ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		installed.selectedGroups = Set.of("main");
		ModpackJsons.ModpackContentFields target = manifest(Map.of(), ledger(entry("config/connector.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT)));
		target.selectedGroups = Set.of("main");
		String baselineHash = "4444444444444444444444444444444444444444";
		Map<String, InstanceTree.TrackedFile> baseline = Map.of("config/connector.json", new InstanceTree.TrackedFile(Root.GAME_DIR, "", "config/connector.json", baselineHash, 8));
		UpdatePlanner.SelectionContext selection = new UpdatePlanner.SelectionContext(installed.modpackId, installed, Map.of(), baseline, Set.of(baselineHash), Set.of());

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, target,
				Map.of(new FileKey(Root.GAME_DIR, "config/connector.json"), new FileState(OLD_HASH, 8, true)), Set.of(), List.of(), List.of(), List.of(), List.of(), selection,
				new ClientConfigJsons.ClientConfigFieldsV3()));

		assertEquals(OperationType.DELETE, plan.operations().stream().filter(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals("config/connector.json"))
				.findFirst().orElseThrow().operation());
	}
}
