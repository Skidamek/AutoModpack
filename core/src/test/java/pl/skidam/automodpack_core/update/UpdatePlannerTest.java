package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.update.UpdatePlan.*;

class UpdatePlannerTest {
	private static final String TARGET_HASH = "1111111111111111111111111111111111111111";
	private static final String OLD_HASH = "2222222222222222222222222222222222222222";

	@Test
	void projectsSafeRemoteDeletionsBeforeDuplicateReconciliationDeterministically() {
		Jsons.ModpackContentFields target = manifest();
		Map<FileKey, FileState> files = new HashMap<>();
		files.put(new FileKey(Root.MODS_DIR, "old.jar"), new FileState(OLD_HASH, 8, true, true));
		files.put(new FileKey(Root.MODPACK_DIR, "mods/new.jar"), new FileState(TARGET_HASH, 9, true, true));
		ModInfo targetMod = new ModInfo("mods/new.jar", TARGET_HASH, 9, Set.of("same-id"), Set.of());
		ModInfo oldStandard = new ModInfo("old.jar", OLD_HASH, 8, Set.of("same-id"), Set.of());

		UpdatePlan first = UpdatePlanner.plan(input(target, files, true, List.of(targetMod), List.of(oldStandard)));
		UpdatePlan second = UpdatePlanner.plan(input(target, new LinkedHashMap<>(files), true, List.of(targetMod), List.of(oldStandard)));

		assertEquals(first.operations(), second.operations());
		assertEquals(first.projectedFinalState(), second.projectedFinalState());
		assertEquals(first.warnings(), second.warnings());
		assertEquals(Set.of("delete-1"), first.plannedDeletionTimestamps());
		assertEquals(1, first.operations().stream().filter(operation -> operation.operation() == OperationType.DELETE).count());
		assertTrue(first.operations().stream().anyMatch(operation -> operation.root() == Root.MODS_DIR && operation.relativePath().equals("old.jar")
				&& operation.expectedExistingHash().equals(OLD_HASH)));
	}

	@Test
	void remoteDeletionOptOutAndHashMismatchProduceDeterministicSafeDecisions() {
		Jsons.ModpackContentFields target = manifest();
		Map<FileKey, FileState> mismatch = Map.of(new FileKey(Root.MODS_DIR, "old.jar"), new FileState(TARGET_HASH, 8, true, true));

		UpdatePlan disabled = UpdatePlanner.plan(input(target, mismatch, false, List.of(), List.of()));
		UpdatePlan wrongHash = UpdatePlanner.plan(input(target, mismatch, true, List.of(), List.of()));

		assertTrue(disabled.operations().stream().noneMatch(operation -> operation.operation() == OperationType.DELETE));
		assertTrue(disabled.plannedDeletionTimestamps().isEmpty());
		assertEquals(List.of(new Warning(WarningType.REMOTE_DELETION_DISABLED, "delete-1", "mods/old.jar", OLD_HASH, null, null)), disabled.warnings());

		assertTrue(wrongHash.operations().stream().noneMatch(operation -> operation.operation() == OperationType.DELETE));
		assertEquals(Set.of("delete-1"), wrongHash.plannedDeletionTimestamps());
		assertEquals(List.of(new Warning(WarningType.REMOTE_DELETION_HASH_MISMATCH, "delete-1", "mods/old.jar", OLD_HASH, "mods/old.jar", TARGET_HASH)),
				wrongHash.warnings());
	}

	private static UpdatePlanner.Input input(Jsons.ModpackContentFields target, Map<FileKey, FileState> files, boolean allow,
			List<ModInfo> targetMods, List<ModInfo> standardMods) {
		return new UpdatePlanner.Input(null, target, files, allow, Set.of(), Set.of(), targetMods, standardMods, List.of(), new Jsons.ClientConfigFieldsV3());
	}

	private static Jsons.ModpackContentFields manifest() {
		Jsons.ModpackContentFields target = new Jsons.ModpackContentFields(Set.of(
				new Jsons.ModpackContentFields.ModpackContentItem("/mods/new.jar", "9", "mod", false, false, false, TARGET_HASH, "0")));
		target.modpackId = "abc1234";
		target.nonModpackFilesToDelete = Set.of(new Jsons.ModpackContentFields.FileToDelete("/mods/old.jar", OLD_HASH, "delete-1"));
		return target;
	}
}
