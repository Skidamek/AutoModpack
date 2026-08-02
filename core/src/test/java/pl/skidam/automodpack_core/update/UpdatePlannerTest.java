package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.update.UpdatePlan.*;

class UpdatePlannerTest {
	private static final String TARGET_HASH = "1111111111111111111111111111111111111111";
	private static final String OLD_HASH = "2222222222222222222222222222222222222222";
	private static final String OTHER_HASH = "3333333333333333333333333333333333333333";

	@Test
	void cleanupUsesHistoricalHashAndSizeForManagedFiles() {
		Jsons.ModpackContentFields target = manifest(Map.of(
				"mods/new.jar", item("mods/new.jar", TARGET_HASH, 9, "mod"),
				"config/kept.json", item("config/kept.json", OTHER_HASH, 4, "config")),
				ledger(entry("mods/old.jar", OLD_HASH, 8, OwnershipLedger.Status.TOMBSTONE),
						entry("mods/new.jar", TARGET_HASH, 9, OwnershipLedger.Status.PRESENT),
						entry("config/kept.json", OTHER_HASH, 4, OwnershipLedger.Status.PRESENT)));
		Map<FileKey, FileState> files = new LinkedHashMap<>();
		files.put(new FileKey(Root.MODS_DIR, "old.jar"), new FileState(OLD_HASH, 8, true, true));
		files.put(new FileKey(Root.MODPACK_DIR, "mods/new.jar"), new FileState(TARGET_HASH, 9, true, true));
		files.put(new FileKey(Root.GAME_DIR, "config/kept.json"), new FileState(OTHER_HASH, 4, true, false));

		UpdatePlan plan = UpdatePlanner.plan(input(target, files));

		assertTrue(plan.operations().stream().anyMatch(operation -> operation.root() == Root.MODS_DIR
				&& operation.relativePath().equals("old.jar") && operation.operation() == OperationType.DELETE
				&& OLD_HASH.equals(operation.expectedExistingHash())));
		assertTrue(plan.restartReasons().contains(RestartReason.APPLIED_SERVER_DELETIONS));
		assertEquals(List.of(new Preservation(Root.MODS_DIR, "old.jar", OLD_HASH, 8)), plan.preservations());
	}

	@Test
	void cleanupPreservesMismatchesUnsafeTypesAndPlayerLocalPaths() {
		String localHash = "4444444444444444444444444444444444444444";
		Jsons.ModpackContentFields target = manifest(Map.of(), ledger(
				entry("config/changed.json", OLD_HASH, 8, OwnershipLedger.Status.TOMBSTONE),
				entry("config/size.json", OLD_HASH, 8, OwnershipLedger.Status.TOMBSTONE),
				entry("config/link.json", OLD_HASH, 8, OwnershipLedger.Status.TOMBSTONE),
				entry("saves/world.dat", localHash, 8, OwnershipLedger.Status.TOMBSTONE)));
		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.GAME_DIR, "config/changed.json"), new FileState(OTHER_HASH, 8, true, false),
				new FileKey(Root.GAME_DIR, "config/size.json"), new FileState(OLD_HASH, 9, true, false),
				new FileKey(Root.GAME_DIR, "config/link.json"), new FileState(OLD_HASH, 8, false, false),
				new FileKey(Root.GAME_DIR, "saves/world.dat"), new FileState(localHash, 8, true, false));

		UpdatePlan plan = UpdatePlanner.plan(input(target, files));

		assertTrue(plan.operations().stream().noneMatch(operation -> operation.operation() == OperationType.DELETE));
		assertTrue(UpdatePlanner.managedCleanupKey("config/changed.json").isPresent());
		assertTrue(UpdatePlanner.managedCleanupKey("config/size.json").isPresent());
		assertTrue(UpdatePlanner.managedCleanupKey("saves/world.dat").isEmpty());
	}

	@Test
	void editableSelectionPreservationRemainsSeparateFromLedgerCleanup() {
		String editedOldHash = "5555555555555555555555555555555555555555";
		String editedTargetHash = "6666666666666666666666666666666666666666";
		Jsons.ModpackContentFields target = new Jsons.ModpackContentFields(Set.of(
				editableItem("config/settings.json", TARGET_HASH, 7, "config")));
		target.modpackId = "abc1234";
		target.targetGenerationId = "1".repeat(40);
		target.parentGenerationId = "";
		target.stateDigest = "2".repeat(40);
		target.ownershipLedger = ledger(entry("config/settings.json", TARGET_HASH, 7, OwnershipLedger.Status.PRESENT));
		Jsons.ModpackContentFields previous = new Jsons.ModpackContentFields(Set.of(
				editableItem("config/settings.json", OLD_HASH, 6, "config")));
		previous.modpackId = "old1234";
		previous.targetGenerationId = "3".repeat(40);
		previous.parentGenerationId = "";
		previous.stateDigest = "4".repeat(40);
		previous.ownershipLedger = ledgerFor("old1234", entry("config/settings.json", OLD_HASH, 6, OwnershipLedger.Status.PRESENT));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "config/settings.json"), new FileState(editedOldHash, 6, true, false),
				new FileKey(Root.MODPACK_DIR, "config/settings.json"), new FileState(editedTargetHash, 7, true, false));
		UpdatePlanner.Input input = new UpdatePlanner.Input(null, target, files, Set.of(), List.of(), List.of(), List.of(),
				new UpdatePlanner.SelectionContext("old1234", previous), new Jsons.ClientConfigFieldsV3());

		UpdatePlan plan = UpdatePlanner.plan(input);

		assertTrue(plan.operations().stream().anyMatch(operation -> operation.root() == Root.AUTOMODPACK_DIR
				&& operation.relativePath().equals("modpacks/old1234/config/settings.json") && operation.expectedObjectHash().equals(editedOldHash)));
		assertTrue(plan.operations().stream().anyMatch(operation -> operation.root() == Root.GAME_DIR
				&& operation.relativePath().equals("config/settings.json") && operation.expectedObjectHash().equals(editedTargetHash)));
		assertTrue(plan.restartReasons().contains(RestartReason.SELECTED_MODPACK));
	}

	private static UpdatePlanner.Input input(Jsons.ModpackContentFields target, Map<FileKey, FileState> files) {
		return new UpdatePlanner.Input(null, target, files, Set.of(), List.of(), List.of(), List.of(), null, new Jsons.ClientConfigFieldsV3());
	}

	private static Jsons.ModpackContentFields manifest(Map<String, Jsons.ModpackContentFields.ModpackContentItem> items,
			Jsons.OwnershipLedgerFields ownershipLedger) {
		Jsons.ModpackContentFields target = new Jsons.ModpackContentFields(new LinkedHashSet<>(items.values()));
		target.modpackId = "abc1234";
		target.targetGenerationId = "1".repeat(40);
		target.parentGenerationId = "";
		target.stateDigest = "2".repeat(40);
		target.ownershipLedger = ownershipLedger;
		return target;
	}

	private static Jsons.ModpackContentFields.ModpackContentItem item(String path, String hash, long size, String type) {
		return new Jsons.ModpackContentFields.ModpackContentItem(path, String.valueOf(size), type, false, false, false, hash, "0");
	}

	private static Jsons.ModpackContentFields.ModpackContentItem editableItem(String path, String hash, long size, String type) {
		return new Jsons.ModpackContentFields.ModpackContentItem(path, String.valueOf(size), type, true, false, false, hash, "0");
	}

	private static Jsons.OwnershipLedgerFields ledger(OwnershipLedger.Entry... entries) {
		return ledgerFor("abc1234", entries);
	}

	private static Jsons.OwnershipLedgerFields ledgerFor(String modpackId, OwnershipLedger.Entry... entries) {
		Map<String, OwnershipLedger.Entry> values = new TreeMap<>();
		for (OwnershipLedger.Entry entry : entries) values.put(entry.logicalPath(), entry);
		return new OwnershipLedger(modpackId, values).toFields();
	}

	private static OwnershipLedger.Entry entry(String path, String hash, long size, OwnershipLedger.Status status) {
		return new OwnershipLedger.Entry(path, Set.of(new OwnershipLedger.Content(hash, size)), Set.of("main"), "a".repeat(40), "b".repeat(40), status);
	}
}
