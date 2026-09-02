package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.update.UpdatePlan.FileKey;
import pl.skidam.automodpack_core.update.UpdatePlan.FileState;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;

class UpdatePreviewTest {
	private static final String TARGET_HASH = "1111111111111111111111111111111111111111";
	private static final String OLD_HASH = "2222222222222222222222222222222222222222";
	private static final String OTHER_HASH = "3333333333333333333333333333333333333333";

	@Test
	void planningProducesTheCanonicalPreviewConsequences() {
		ModpackJsons.ModpackContentFields target = manifest(
				item("config/new.json", TARGET_HASH, 4, "config"), item("config/changed.json", TARGET_HASH, 8, "config"),
				entry("config/new.json", TARGET_HASH, 4, OwnershipLedger.Status.PRESENT),
				entry("config/changed.json", TARGET_HASH, 8, OwnershipLedger.Status.PRESENT),
				entry("config/old.json", OLD_HASH, 7, OwnershipLedger.Status.TOMBSTONE));
		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.GAME_DIR, "config/changed.json"), new FileState(OTHER_HASH, 8, true),
				new FileKey(Root.GAME_DIR, "config/old.json"), new FileState(OLD_HASH, 7, true));

		UpdatePlan decision = plan(target, files);
		UpdatePreview preview = UpdatePreview.create(decision, null, UpdatePreview.Mode.UPDATE);

		assertSame(decision.consequences(), preview.changeSet());
		assertEquals(ChangeSet.Kind.ADDED, change(preview, "config/new.json").kind());
		assertEquals(ChangeSet.Kind.MODIFIED, change(preview, "config/changed.json").kind());
		assertEquals(ChangeSet.Kind.REMOVED, change(preview, "config/old.json").kind());
		ChangeSet.Occurrence changed = change(preview, "config/changed.json").primaryOccurrence();
		assertEquals("config", changed.contentKind());
		assertEquals(OTHER_HASH, changed.beforeHash());
		assertEquals(TARGET_HASH, changed.afterHash());
		assertEquals(List.of("main"), changed.featureIds());
	}

	@Test
	void oneLogicalDispositionKeepsEveryPhysicalOccurrence() {
		ModpackJsons.ModpackContentFields target = manifest(item("test/video.mp4", TARGET_HASH, 9, "other"),
				entry("test/video.mp4", TARGET_HASH, 9, OwnershipLedger.Status.PRESENT));

		UpdatePreview preview = UpdatePreview.create(plan(target, Map.of()), null, UpdatePreview.Mode.UPDATE);

		ChangeSet.Change change = change(preview, "test/video.mp4");
		assertEquals(ChangeSet.Kind.ADDED, change.kind());
		assertEquals(List.of("GAME_DIR", "PROJECTION"), change.occurrences().stream().map(ChangeSet.Occurrence::location).toList());
		assertEquals(9, preview.addedBytes());
		assertEquals(1, preview.summary().changedFiles());
	}

	@Test
	void unavailableOwnedContentIsDecidedDuringPlanning() {
		ModpackJsons.ModpackContentFields target = manifest(entry("config/unknown.json", OLD_HASH, 12, OwnershipLedger.Status.TOMBSTONE));
		Map<FileKey, FileState> files = Map.of(new FileKey(Root.GAME_DIR, "config/unknown.json"), new FileState(null, 12, true));

		UpdatePlan decision = plan(target, files);

		assertEquals(ChangeSet.Kind.PRESERVED_UNAVAILABLE, change(UpdatePreview.create(decision, null, UpdatePreview.Mode.UPDATE), "config/unknown.json").kind());
	}

	@Test
	void removalDoesNotReportLiveFileAlreadyMatchingBaseline() {
		ModpackJsons.ModpackContentFields installed = manifest(item("config/kept.json", OLD_HASH, 7, "config"),
				entry("config/kept.json", OLD_HASH, 7, OwnershipLedger.Status.PRESENT));
		ClientStorageJsons.ClientBaselineFields baseline = new ClientStorageJsons.ClientBaselineFields();
		baseline.modpackId = installed.modpackId;
		ClientStorageJsons.ClientBaselineFields.EntryFields baselineEntry = new ClientStorageJsons.ClientBaselineFields.EntryFields();
		baselineEntry.logicalPath = "config/kept.json";
		baselineEntry.objectHash = OLD_HASH;
		baselineEntry.size = 7;
		baseline.entries = new ArrayList<>(List.of(baselineEntry));
		Map<FileKey, FileState> files = Map.of(
				new FileKey(Root.PROJECTION, "config/kept.json"), new FileState(OLD_HASH, 7, true),
				new FileKey(Root.GAME_DIR, "config/kept.json"), new FileState(OLD_HASH, 7, true));

		UpdatePlan decision = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, baseline, files, Set.of(OLD_HASH), null, new ClientConfigJsons.ClientConfigFieldsV3()));
		ChangeSet.Change change = change(UpdatePreview.create(decision, null, UpdatePreview.Mode.REMOVAL), "config/kept.json");

		assertEquals(ChangeSet.Kind.REMOVED, change.kind());
		assertEquals(List.of("PROJECTION"), change.occurrences().stream().map(ChangeSet.Occurrence::location).toList());
	}

	@Test
	void presentationNamesDoNotReclassifyTheDecision() {
		ModpackJsons.ModpackContentFields target = manifest(item("mods/example.jar", TARGET_HASH, 9, "mod"),
				entry("mods/example.jar", TARGET_HASH, 9, OwnershipLedger.Status.PRESENT));
		UpdatePreview preview = UpdatePreview.create(plan(target, Map.of()), null, UpdatePreview.Mode.UPDATE);
		GroupManifest.Group feature = new GroupManifest.Group("Main feature", "", "", true, true, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("mods/example.jar", new GroupManifest.GroupFile(9, "mod", false, TARGET_HASH, "0"))));
		GroupManifest manifest = new GroupManifest("abc1234", "", "", "", "", "", new TreeMap<>(Map.of("main", feature)));

		UpdatePreview named = preview.withFeatureManifest(manifest);

		assertEquals(change(preview, "mods/example.jar").kind(), change(named, "mods/example.jar").kind());
		assertEquals(List.of("main"), change(named, "mods/example.jar").primaryOccurrence().featureIds());
		assertEquals("Main feature", named.featureNames().get("main"));
	}

	@Test
	void addingRestartReasonUpdatesDecisionAndConsequencesTogether() {
		UpdatePlan decision = plan(manifest(), Map.of()).withRestartReason(UpdatePlan.RestartReason.CHANGED_LOADER_VERSION);

		assertTrue(decision.restartReasons().contains(UpdatePlan.RestartReason.CHANGED_LOADER_VERSION));
		assertTrue(decision.consequences().effects().contains(new ChangeSet.Effect("restart", "CHANGED_LOADER_VERSION")));
	}

	private static ChangeSet.Change change(UpdatePreview preview, String path) {
		return preview.changeSet().changes().stream().filter(change -> change.logicalPath().equals(path)).findFirst().orElseThrow();
	}

	private static UpdatePlan plan(ModpackJsons.ModpackContentFields target, Map<FileKey, FileState> files) {
		return UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Map.of(), Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3()));
	}

	private static ModpackJsons.ModpackContentFields.ModpackContentItem item(String path, String hash, long size, String type) {
		return new ModpackJsons.ModpackContentFields.ModpackContentItem(path, Long.toString(size), type, false, hash, "0");
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
