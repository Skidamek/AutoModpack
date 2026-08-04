package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePlan.FileKey;
import pl.skidam.automodpack_core.update.UpdatePlan.FileState;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;

class UpdatePreviewTest {
	private static final String TARGET_HASH = "1111111111111111111111111111111111111111";
	private static final String OLD_HASH = "2222222222222222222222222222222222222222";
	private static final String OTHER_HASH = "3333333333333333333333333333333333333333";

	@Test
	void reportsAddsChangesRemovalsAndCasPreservation() {
		Jsons.ModpackContentFields target = manifest(
				new Jsons.ModpackContentFields.ModpackContentItem("config/new.json", "4", "config", false, false, false, TARGET_HASH, "0"),
				new Jsons.ModpackContentFields.ModpackContentItem("config/changed.json", "8", "config", false, false, false, TARGET_HASH, "0"),
				entry("config/new.json", TARGET_HASH, 4, OwnershipLedger.Status.PRESENT),
				entry("config/changed.json", TARGET_HASH, 8, OwnershipLedger.Status.PRESENT),
				entry("config/old.json", OLD_HASH, 7, OwnershipLedger.Status.TOMBSTONE));
		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.GAME_DIR, "config/changed.json"), new FileState(OTHER_HASH, 8, true, false),
				new FileKey(Root.GAME_DIR, "config/old.json"), new FileState(OLD_HASH, 7, true, false));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Set.of(), List.of(), List.of(), List.of(), null,
				new Jsons.ClientConfigFieldsV3()));
		UpdatePreview preview = UpdatePreview.create(plan, files, target, null, false);

		assertTrue(preview.entries().stream().anyMatch(entry -> entry.kind() == UpdatePreview.Kind.ADDED && entry.relativePath().equals("config/new.json")));
		assertTrue(preview.entries().stream().anyMatch(entry -> entry.kind() == UpdatePreview.Kind.CHANGED && entry.relativePath().equals("config/changed.json")));
		assertTrue(preview.entries().stream().anyMatch(entry -> entry.kind() == UpdatePreview.Kind.REMOVED && entry.relativePath().equals("config/old.json")));
		assertTrue(preview.entries().stream().anyMatch(entry -> entry.kind() == UpdatePreview.Kind.PRESERVED_CAS && entry.relativePath().equals("config/old.json")));
	}

	@Test
	void exposesAcquisitionBytesAndPlanRestartReasons() {
		Jsons.ModpackContentFields target = manifest(
				new Jsons.ModpackContentFields.ModpackContentItem("config/new.json", "4", "config", false, false, false, TARGET_HASH, "0"),
				entry("config/new.json", TARGET_HASH, 4, OwnershipLedger.Status.PRESENT));
		Map<FileKey, FileState> files = Map.of();
		UpdatePlan planned = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Set.of(), List.of(), List.of(), List.of(), null, new Jsons.ClientConfigFieldsV3()));
		UpdatePlan plan = new UpdatePlan(planned.modpackId(), planned.generationTarget(), planned.operations(), planned.projectedFinalState(), planned.plannedClientConfig(),
				Set.of(RestartReason.CORRECTED_FILE_LOCATIONS), planned.preservations(), planned.baselineCaptures());

		UpdatePreview preview = UpdatePreview.create(plan, files, target, null, false);

		assertEquals(4, preview.uncachedAcquisitionBytes());
		assertEquals(plan.restartReasons(), preview.restartReasons());
	}

	@Test
	void normalizesTargetPathsBeforeLedgerComparison() {
		Jsons.ModpackContentFields target = manifest(
				new Jsons.ModpackContentFields.ModpackContentItem("/config/kept.json", "8", "config", false, false, false, OLD_HASH, "0"),
				entry("config/kept.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "config/kept.json"), new FileState(OLD_HASH, 8, true, false));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Set.of(), List.of(), List.of(), List.of(), null,
				new Jsons.ClientConfigFieldsV3()));
		UpdatePreview preview = UpdatePreview.create(plan, files, target, null, false);

		assertTrue(preview.entries().stream().noneMatch(entry -> entry.relativePath().equals("config/kept.json")
				&& (entry.kind() == UpdatePreview.Kind.PRESERVED_CHANGED || entry.kind() == UpdatePreview.Kind.PRESERVED_OUTSIDE
						|| entry.kind() == UpdatePreview.Kind.PRESERVED_UNAVAILABLE)));
	}

	@Test
	void reportsUnavailableHashWithoutThrowing() {
		Jsons.ModpackContentFields target = manifest(entry("config/unknown.json", OLD_HASH, 12, OwnershipLedger.Status.TOMBSTONE));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "config/unknown.json"), new FileState(null, 12, true, false));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Set.of(), List.of(), List.of(), List.of(), null,
				new Jsons.ClientConfigFieldsV3()));
		UpdatePreview preview = UpdatePreview.create(plan, files, target, null, false);

		assertEquals(UpdatePreview.Kind.PRESERVED_UNAVAILABLE, assertSingle(preview, "config/unknown.json").kind());
	}

	@Test
	void removalPreviewShowsDeletionAndCasPreservation() {
		Jsons.ModpackContentFields installed = manifest(
				new Jsons.ModpackContentFields.ModpackContentItem("config/removed.json", "7", "config", false, false, false, OLD_HASH, "0"),
				entry("config/removed.json", OLD_HASH, 7, OwnershipLedger.Status.PRESENT));
		Jsons.ClientBaselineFields baseline = new Jsons.ClientBaselineFields();
		baseline.modpackId = installed.modpackId;
		Jsons.ClientBaselineFields.EntryFields baselineEntry = new Jsons.ClientBaselineFields.EntryFields();
		baselineEntry.logicalPath = "config/removed.json";
		baselineEntry.objectHash = "";
		baselineEntry.size = -1;
		baselineEntry.absent = true;
		baseline.entries = new ArrayList<>(List.of(baselineEntry));
		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.PROJECTION, "config/removed.json"), new FileState(OLD_HASH, 7, true, false),
				new FileKey(Root.GAME_DIR, "config/removed.json"), new FileState(OLD_HASH, 7, true, false));
		UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, baseline, files, Set.of(), new Jsons.ClientConfigFieldsV3()));

		UpdatePreview preview = UpdatePreview.create(plan, files, installed, null, true);

		assertTrue(preview.entries().stream().anyMatch(entry -> entry.kind() == UpdatePreview.Kind.REMOVED && entry.relativePath().equals("config/removed.json")));
		assertTrue(preview.entries().stream().anyMatch(entry -> entry.kind() == UpdatePreview.Kind.PRESERVED_CAS && entry.relativePath().equals("config/removed.json")));
	}

	@Test
	void removalPreviewHidesFilesAlreadyMatchingBaseline() {
		Jsons.ModpackContentFields installed = manifest(
				new Jsons.ModpackContentFields.ModpackContentItem("config/kept.json", "7", "config", false, false, false, OLD_HASH, "0"),
				entry("config/kept.json", OLD_HASH, 7, OwnershipLedger.Status.PRESENT));
		Jsons.ClientBaselineFields baseline = new Jsons.ClientBaselineFields();
		baseline.modpackId = installed.modpackId;
		Jsons.ClientBaselineFields.EntryFields baselineEntry = new Jsons.ClientBaselineFields.EntryFields();
		baselineEntry.logicalPath = "config/kept.json";
		baselineEntry.objectHash = OLD_HASH;
		baselineEntry.size = 7;
		baseline.entries = List.of(baselineEntry);
		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.PROJECTION, "config/kept.json"), new FileState(OLD_HASH, 7, true, false),
				new FileKey(Root.GAME_DIR, "config/kept.json"), new FileState(OLD_HASH, 7, true, false));
		UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, baseline, files, Set.of(OLD_HASH), new Jsons.ClientConfigFieldsV3()));

		UpdatePreview preview = UpdatePreview.create(plan, files, installed, null, true, baseline);

		assertTrue(preview.entries().stream().noneMatch(entry -> entry.root() == Root.GAME_DIR && entry.relativePath().equals("config/kept.json")));
	}

	@Test
	void includesExplicitResolvedAndStaleGroupConsequences() {
		Jsons.ModpackContentFields target = manifest();
		Map<FileKey, FileState> files = Map.of();
		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Set.of(), List.of(), List.of(), List.of(), null,
				new Jsons.ClientConfigFieldsV3()));
		ResolvedSelection selection = new ResolvedSelection(new SelectionIntent(Set.of("optional")), new TreeSet<>(Set.of("main", "optional")),
				new TreeSet<>(Set.of("stale")));

		UpdatePreview preview = UpdatePreview.create(plan, files, target, selection, false, "Patch notes");

		assertEquals("Patch notes", preview.patchNotes());
		assertEquals(Set.of(), preview.groupConsequences().explicitTags());
		assertEquals(Set.of("optional"), preview.groupConsequences().explicitGroups());
		assertEquals(Set.of("main", "optional"), preview.groupConsequences().resolvedGroups());
		assertEquals(Set.of("stale"), preview.groupConsequences().staleGroups());
	}

	private static UpdatePreview.Entry assertSingle(UpdatePreview preview, String path) {
		return preview.entries().stream().filter(entry -> entry.relativePath().equals(path)).findFirst().orElseThrow();
	}

	private static Jsons.ModpackContentFields manifest(Object... values) {
		Jsons.ModpackContentFields target = new Jsons.ModpackContentFields(new LinkedHashSet<>());
		Map<String, OwnershipLedger.Entry> ledgerEntries = new TreeMap<>();
		for (Object value : values) {
			if (value instanceof Jsons.ModpackContentFields.ModpackContentItem item) target.list.add(item);
			if (value instanceof OwnershipLedger.Entry entry) ledgerEntries.put(entry.logicalPath(), entry);
		}
		target.modpackId = "abc1234";
		target.targetGenerationId = "1".repeat(40);
		target.parentGenerationId = "";
		target.stateDigest = "2".repeat(40);
		target.ownershipLedger = new OwnershipLedger("abc1234", ledgerEntries).toFields();
		return target;
	}

	private static OwnershipLedger.Entry entry(String path, String hash, long size, OwnershipLedger.Status status) {
		return new OwnershipLedger.Entry(path, Set.of(new OwnershipLedger.Content(hash, size)), Set.of("main"), "a".repeat(40), "b".repeat(40), status);
	}
}
