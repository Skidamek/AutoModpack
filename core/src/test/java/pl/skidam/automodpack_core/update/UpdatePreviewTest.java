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

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePlan.FileKey;
import pl.skidam.automodpack_core.update.UpdatePlan.FileState;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;

class UpdatePreviewTest {
	private static final String TARGET_HASH = "1111111111111111111111111111111111111111";
	private static final String OLD_HASH = "2222222222222222222222222222222222222222";
	private static final String OTHER_HASH = "3333333333333333333333333333333333333333";

	@Test
	void reportsAddsChangesRemovalsAndCasPreservation() {
		ModpackJsons.ModpackContentFields target = manifest(
				new ModpackJsons.ModpackContentFields.ModpackContentItem("config/new.json", "4", "config", false, false, TARGET_HASH, "0"),
				new ModpackJsons.ModpackContentFields.ModpackContentItem("config/changed.json", "8", "config", false, false, TARGET_HASH, "0"),
				entry("config/new.json", TARGET_HASH, 4, OwnershipLedger.Status.PRESENT),
				entry("config/changed.json", TARGET_HASH, 8, OwnershipLedger.Status.PRESENT),
				entry("config/old.json", OLD_HASH, 7, OwnershipLedger.Status.TOMBSTONE));
		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.GAME_DIR, "config/changed.json"), new FileState(OTHER_HASH, 8, true),
				new FileKey(Root.GAME_DIR, "config/old.json"), new FileState(OLD_HASH, 7, true));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3()));
		UpdatePreview preview = UpdatePreview.create(plan, files, target, null, false);

		assertTrue(preview.entries().stream().anyMatch(entry -> entry.kind() == UpdatePreview.Kind.ADDED && entry.relativePath().equals("config/new.json")));
		assertTrue(preview.entries().stream().anyMatch(entry -> entry.kind() == UpdatePreview.Kind.CHANGED && entry.relativePath().equals("config/changed.json")));
		assertTrue(preview.entries().stream().anyMatch(entry -> entry.kind() == UpdatePreview.Kind.REMOVED && entry.relativePath().equals("config/old.json")));
		var changed = preview.changeSet().changes().stream().filter(change -> change.logicalPath().equals("config/changed.json")).findFirst().orElseThrow().primaryOccurrence();
		assertEquals("config", changed.contentKind());
		assertEquals(OTHER_HASH, changed.beforeHash());
		assertEquals(TARGET_HASH, changed.afterHash());
		assertEquals(List.of("main"), changed.featureIds());

		GroupManifest.Group feature = new GroupManifest.Group("Main feature", "", "", "", true, true, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("config/changed.json", new GroupManifest.GroupFile(8, "config", false, false, TARGET_HASH, "0"))));
		GroupManifest featureManifest = new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(Map.of("main", feature)));
		UpdatePreview named = preview.withFeatureManifest(featureManifest);
		assertEquals("Main feature", named.featureNames().get("main"));
	}

	@Test
	void displaysManagedProjectionAndLiveCopyAsOneLogicalFile() {
		ModpackJsons.ModpackContentFields target = manifest(
				new ModpackJsons.ModpackContentFields.ModpackContentItem("test/video.mp4", "9", "other", false, false, TARGET_HASH, "0"),
				entry("test/video.mp4", TARGET_HASH, 9, OwnershipLedger.Status.PRESENT));
		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, Map.of(), Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3()));

		UpdatePreview preview = UpdatePreview.create(plan, Map.of(), target, null, false);

		assertEquals(List.of(new UpdatePreview.Entry(UpdatePreview.Kind.ADDED, Root.PROJECTION, "test/video.mp4", 9)), preview.entries());
		assertEquals(1, preview.summary().changedFiles());
	}

	@Test
	void combinesDifferentPhysicalEffectsForOneLogicalPath() {
		ModpackJsons.ModpackContentFields target = manifest();
		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, Map.of(), Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3()));

		UpdatePreview preview = new UpdatePreview(plan, List.of(
				new UpdatePreview.Entry(UpdatePreview.Kind.REMOVED, Root.PROJECTION, "config/shared.json", 4),
				new UpdatePreview.Entry(UpdatePreview.Kind.CHANGED, Root.GAME_DIR, "config/shared.json", 8)),
				new UpdatePreview.GroupConsequences(Set.of(), Set.of(), Set.of()));

		assertEquals(List.of(new UpdatePreview.Entry(UpdatePreview.Kind.CHANGED, Root.PROJECTION, "config/shared.json", 8)), preview.entries());
	}

	@Test
	void exposesAcquisitionBytesAndPlanRestartReasons() {
		ModpackJsons.ModpackContentFields target = manifest(
				new ModpackJsons.ModpackContentFields.ModpackContentItem("config/new.json", "4", "config", false, false, TARGET_HASH, "0"),
				entry("config/new.json", TARGET_HASH, 4, OwnershipLedger.Status.PRESENT));
		Map<FileKey, FileState> files = Map.of();
		UpdatePlan planned = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null, new ClientConfigJsons.ClientConfigFieldsV3()));
		UpdatePlan plan = new UpdatePlan(planned.modpackId(), planned.generationTarget(), planned.operations(), planned.projectedFinalState(), planned.plannedClientConfig(),
				Set.of(RestartReason.CORRECTED_FILE_LOCATIONS), planned.preservations(), planned.baselineCaptures(), planned.conflicts(), planned.generatedCopies());

		UpdatePreview preview = UpdatePreview.create(plan, files, target, null, false);

		assertEquals(4, preview.uncachedAcquisitionBytes());
		assertEquals(plan.restartReasons(), preview.restartReasons());
	}

	@Test
	void classifiesGuardedReplacementOfExistingRegularFileAsChanged() {
		ModpackJsons.ModpackContentFields target = manifest();
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "config/settings.json"), new FileState(OLD_HASH, 7, true));
		UpdatePlan plan = planWithOperation(target, new Operation(Root.GAME_DIR, "config/settings.json", OperationType.INSTALL_OBJECT, TARGET_HASH, 9, OLD_HASH));
		UpdatePreview preview = UpdatePreview.create(plan, files, target, null, false);

		assertEquals(UpdatePreview.Kind.CHANGED, assertSingle(preview, "config/settings.json").kind());
		assertEquals(1, preview.summary().changedFiles());
		assertEquals(9, preview.changedBytes());
	}

	@Test
	void classifiesGuardedInstallAtAbsentPathAsAdded() {
		ModpackJsons.ModpackContentFields target = manifest();
		UpdatePlan plan = planWithOperation(target, new Operation(Root.GAME_DIR, "config/settings.json", OperationType.INSTALL_OBJECT, TARGET_HASH, 9, OLD_HASH));
		UpdatePreview preview = UpdatePreview.create(plan, Map.of(), target, null, false);

		assertEquals(UpdatePreview.Kind.ADDED, assertSingle(preview, "config/settings.json").kind());
		assertEquals(1, preview.summary().changedFiles());
		assertEquals(9, preview.addedBytes());
	}

	@Test
	void summaryDeduplicatesLogicalPathsAndLabelsOtherEffects() {
		ModpackJsons.ModpackContentFields target = manifest();
		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, Map.of(), Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3()));
		UpdatePreview preview = new UpdatePreview(plan, List.of(
				new UpdatePreview.Entry(UpdatePreview.Kind.PRESERVED_CHANGED, Root.GAME_DIR, "config/preserved.json", 1),
				new UpdatePreview.Entry(UpdatePreview.Kind.CHANGED, Root.PROJECTION, "config/shared.json", 2),
				new UpdatePreview.Entry(UpdatePreview.Kind.REMOVED, Root.GAME_DIR, "config/removed.json", 3)),
				new UpdatePreview.GroupConsequences(Set.of("optional"), Set.of("main"), Set.of("stale")));

		assertEquals(new UpdatePreview.Summary(1, 1, 1, 0, 1), preview.summary());
		assertEquals(List.of(UpdatePreview.Kind.REMOVED, UpdatePreview.Kind.CHANGED, UpdatePreview.Kind.PRESERVED_CHANGED), preview.entries().stream().map(UpdatePreview.Entry::kind).toList());
	}

	@Test
	void classifiesKindsForSummaryPrecedenceAndDisplay() {
		assertEquals(UpdatePreview.SummaryBucket.CHANGED, UpdatePreview.Kind.ADDED.summaryBucket());
		assertEquals(UpdatePreview.SummaryBucket.REMOVED, UpdatePreview.Kind.REMOVED.summaryBucket());
		assertEquals(UpdatePreview.SummaryBucket.PRESERVED, UpdatePreview.Kind.PRESERVED_CHANGED.summaryBucket());
		assertEquals(UpdatePreview.SummaryBucket.UNSAFE, UpdatePreview.Kind.UNSAFE.summaryBucket());
		assertTrue(UpdatePreview.Kind.PRESERVED_OUTSIDE.isPreserved());
		assertFalse(UpdatePreview.Kind.CHANGED.isPreserved());
		assertEquals(UpdatePreview.SortBucket.UNSAFE, UpdatePreview.Kind.UNSAFE.sortBucket());
		assertEquals(UpdatePreview.SortBucket.REMOVED, UpdatePreview.Kind.REMOVED.sortBucket());
		assertEquals(UpdatePreview.SortBucket.CHANGED, UpdatePreview.Kind.CHANGED.sortBucket());
		assertEquals(UpdatePreview.SortBucket.PRESERVED, UpdatePreview.Kind.PRESERVED_CHANGED.sortBucket());
		assertEquals("+ ", UpdatePreview.Kind.ADDED.displaySymbol());
		assertEquals("~ ", UpdatePreview.Kind.CHANGED.displaySymbol());
		assertEquals("- ", UpdatePreview.Kind.REMOVED.displaySymbol());
		assertEquals("! ", UpdatePreview.Kind.UNSAFE.displaySymbol());
		assertEquals("  ", UpdatePreview.Kind.PRESERVED_CHANGED.displaySymbol());
	}

	@Test
	void normalizesTargetPathsBeforeLedgerComparison() {
		ModpackJsons.ModpackContentFields target = manifest(
				new ModpackJsons.ModpackContentFields.ModpackContentItem("/config/kept.json", "8", "config", false, false, OLD_HASH, "0"),
				entry("config/kept.json", OLD_HASH, 8, OwnershipLedger.Status.PRESENT));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "config/kept.json"), new FileState(OLD_HASH, 8, true));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3()));
		UpdatePreview preview = UpdatePreview.create(plan, files, target, null, false);

		assertTrue(preview.entries().stream().noneMatch(entry -> entry.relativePath().equals("config/kept.json")
				&& (entry.kind() == UpdatePreview.Kind.PRESERVED_CHANGED || entry.kind() == UpdatePreview.Kind.PRESERVED_OUTSIDE
						|| entry.kind() == UpdatePreview.Kind.PRESERVED_UNAVAILABLE)));
	}

	@Test
	void reportsUnavailableHashWithoutThrowing() {
		ModpackJsons.ModpackContentFields target = manifest(entry("config/unknown.json", OLD_HASH, 12, OwnershipLedger.Status.TOMBSTONE));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "config/unknown.json"), new FileState(null, 12, true));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3()));
		UpdatePreview preview = UpdatePreview.create(plan, files, target, null, false);

		assertEquals(UpdatePreview.Kind.PRESERVED_UNAVAILABLE, assertSingle(preview, "config/unknown.json").kind());
	}

	@Test
	void removalPreviewShowsDeletionAndCasPreservation() {
		ModpackJsons.ModpackContentFields installed = manifest(
				new ModpackJsons.ModpackContentFields.ModpackContentItem("config/removed.json", "7", "config", false, false, OLD_HASH, "0"),
				entry("config/removed.json", OLD_HASH, 7, OwnershipLedger.Status.PRESENT));
		ClientStorageJsons.ClientBaselineFields baseline = new ClientStorageJsons.ClientBaselineFields();
		baseline.modpackId = installed.modpackId;
		ClientStorageJsons.ClientBaselineFields.EntryFields baselineEntry = new ClientStorageJsons.ClientBaselineFields.EntryFields();
		baselineEntry.logicalPath = "config/removed.json";
		baselineEntry.objectHash = "";
		baselineEntry.size = -1;
		baselineEntry.absent = true;
		baseline.entries = new ArrayList<>(List.of(baselineEntry));
		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.PROJECTION, "config/removed.json"), new FileState(OLD_HASH, 7, true),
				new FileKey(Root.GAME_DIR, "config/removed.json"), new FileState(OLD_HASH, 7, true));
		UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, baseline, files, Set.of(), null, new ClientConfigJsons.ClientConfigFieldsV3()));

		UpdatePreview preview = UpdatePreview.create(plan, files, installed, null, true);

		assertTrue(preview.entries().stream().anyMatch(entry -> entry.kind() == UpdatePreview.Kind.REMOVED && entry.relativePath().equals("config/removed.json")));
		assertEquals(0, preview.summary().preservedFiles());
	}

	@Test
	void removalPreviewHidesFilesAlreadyMatchingBaseline() {
		ModpackJsons.ModpackContentFields installed = manifest(
				new ModpackJsons.ModpackContentFields.ModpackContentItem("config/kept.json", "7", "config", false, false, OLD_HASH, "0"),
				entry("config/kept.json", OLD_HASH, 7, OwnershipLedger.Status.PRESENT));
		ClientStorageJsons.ClientBaselineFields baseline = new ClientStorageJsons.ClientBaselineFields();
		baseline.modpackId = installed.modpackId;
		ClientStorageJsons.ClientBaselineFields.EntryFields baselineEntry = new ClientStorageJsons.ClientBaselineFields.EntryFields();
		baselineEntry.logicalPath = "config/kept.json";
		baselineEntry.objectHash = OLD_HASH;
		baselineEntry.size = 7;
		baseline.entries = List.of(baselineEntry);
		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.PROJECTION, "config/kept.json"), new FileState(OLD_HASH, 7, true),
				new FileKey(Root.GAME_DIR, "config/kept.json"), new FileState(OLD_HASH, 7, true));
		UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, baseline, files, Set.of(OLD_HASH), null, new ClientConfigJsons.ClientConfigFieldsV3()));

		UpdatePreview preview = UpdatePreview.create(plan, files, installed, null, true, baseline);

		assertTrue(preview.entries().stream().noneMatch(entry -> entry.root() == Root.GAME_DIR && entry.relativePath().equals("config/kept.json")));
	}

	@Test
	void includesExplicitResolvedAndStaleGroupConsequences() {
		ModpackJsons.ModpackContentFields target = manifest();
		Map<FileKey, FileState> files = Map.of();
		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3()));
		ResolvedSelection selection = new ResolvedSelection(new SelectionIntent(Set.of("optional")), new TreeSet<>(Set.of("main", "optional")),
				new TreeSet<>(Set.of("stale")));

		UpdatePreview preview = UpdatePreview.create(plan, files, target, selection, false, "Patch notes");

		assertEquals("Patch notes", preview.patchNotes());
		assertEquals(Set.of("optional"), preview.groupConsequences().explicitGroups());
		assertEquals(Set.of("main", "optional"), preview.groupConsequences().resolvedGroups());
		assertEquals(Set.of("stale"), preview.groupConsequences().staleGroups());
	}

	private static UpdatePreview.Entry assertSingle(UpdatePreview preview, String path) {
		return preview.entries().stream().filter(entry -> entry.relativePath().equals(path)).findFirst().orElseThrow();
	}

	private static UpdatePlan planWithOperation(ModpackJsons.ModpackContentFields target, Operation operation) {
		UpdatePlan planned = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, Map.of(), Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3()));
		return new UpdatePlan(planned.modpackId(), planned.generationTarget(), List.of(operation), List.of(), planned.plannedClientConfig(), Set.of(), List.of(), List.of(), List.of(), List.of());
	}

	private static ModpackJsons.ModpackContentFields manifest(Object... values) {
		ModpackJsons.ModpackContentFields target = new ModpackJsons.ModpackContentFields(new LinkedHashSet<>());
		Map<String, OwnershipLedger.Entry> ledgerEntries = new TreeMap<>();
		for (Object value : values) {
			if (value instanceof ModpackJsons.ModpackContentFields.ModpackContentItem item) target.list.add(item);
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
