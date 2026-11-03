package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.skidam.automodpack_core.update.UpdatePlannerTest.item;
import static pl.skidam.automodpack_core.update.UpdatePlannerTest.manifest;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.update.UpdatePlan.*;

/** Which of the server's deletions a sync applies: all on first install and switch, only unseen ones on an update of the same pack. */
class ServerTombstonePlanTest {
	private static final String OLD = "5555555555555555555555555555555555555555";
	private static final String NEWLY_DROPPED = "6666666666666666666666666666666666666666";
	private static final String KEPT = "7777777777777777777777777777777777777777";

	@Test
	void firstInstallCleansTombstonesFromLongAgo() {
		ModpackJsons.ModpackContentFields target = pack("abc1234", tombstone("mods/old.jar", OLD), tombstone("config/old.toml", OLD));

		UpdatePlan plan = plan(null, target, Map.of(game("mods/old.jar"), state(OLD), game("config/old.toml"), state(OLD)), List.of(), List.of());

		assertDeleted(plan, "mods/old.jar");
		assertDeleted(plan, "config/old.toml");
		assertTrue(plan.preservations().stream().allMatch(preservation -> preservation.proof() == PreservationProof.SERVER_LEDGER));
	}

	@Test
	void anUpdateCleansOnlyTombstonesNewSinceTheInstalledVersion() {
		ModpackJsons.ModpackContentFields installed = pack("abc1234", present("mods/kept.jar", KEPT), present("mods/dropped.jar", NEWLY_DROPPED));
		installed.list = Set.of(item("mods/kept.jar", KEPT, 8, "mod"), item("mods/dropped.jar", NEWLY_DROPPED, 8, "mod"));
		ModpackJsons.ModpackContentFields target = pack("abc1234", present("mods/kept.jar", KEPT), tombstone("mods/dropped.jar", NEWLY_DROPPED),
				tombstone("mods/away.jar", OLD));
		target.list = Set.of(item("mods/kept.jar", KEPT, 8, "mod"));

		UpdatePlan plan = plan(installed, target, Map.of(game("mods/kept.jar"), state(KEPT), game("mods/away.jar"), state(OLD), game("mods/dropped.jar"), state(NEWLY_DROPPED)),
				List.of(), List.of());

		assertDeleted(plan, "mods/away.jar");
		assertDeleted(plan, "mods/dropped.jar");
		assertTrue(op(plan, "mods/kept.jar").filter(operation -> operation.operation() == OperationType.DELETE).isEmpty());
	}

	@Test
	void anUpdateCleansAHandDroppedCopyOfANewlyDeletedVersion() {
		ModpackJsons.ModpackContentFields installed = pack("abc1234", present("mods/lib.jar", KEPT));
		installed.list = Set.of(item("mods/lib.jar", KEPT, 8, "mod"));
		ModpackJsons.ModpackContentFields target = pack("abc1234", entry("mods/lib.jar", OwnershipLedger.Status.TOMBSTONE, KEPT, OLD));

		UpdatePlan plan = plan(installed, target, Map.of(game("mods/lib.jar"), state(OLD)), List.of(), List.of());

		assertDeleted(plan, "mods/lib.jar");
	}

	@Test
	void anUpdateNeverReappliesAnOldTombstoneSoAConfigThePlayerPutBackStays() {
		ModpackJsons.ModpackContentFields installed = pack("abc1234", tombstone("config/needed.toml", OLD));
		ModpackJsons.ModpackContentFields target = pack("abc1234", tombstone("config/needed.toml", OLD), present("config/new.toml", KEPT));

		UpdatePlan plan = plan(installed, target, Map.of(game("config/needed.toml"), state(OLD)), List.of(), List.of());

		assertTrue(op(plan, "config/needed.toml").isEmpty());
	}

	@Test
	void aPathShippedAgainAndDeletedAgainWhileAwayIsNewAgain() {
		ModpackJsons.ModpackContentFields installed = pack("abc1234", tombstone("config/back.toml", OLD));
		ModpackJsons.ModpackContentFields target = pack("abc1234", entry("config/back.toml", OwnershipLedger.Status.TOMBSTONE, OLD, NEWLY_DROPPED));

		UpdatePlan plan = plan(installed, target, Map.of(game("config/back.toml"), state(NEWLY_DROPPED)), List.of(), List.of());

		assertDeleted(plan, "config/back.toml");
	}

	@Test
	void aSwitchAppliesEveryTombstoneOfTheNewPackLikeAFreshInstall() {
		ModpackJsons.ModpackContentFields a = pack("aaa1111", present("config/a.toml", KEPT));
		ModpackJsons.ModpackContentFields b = pack("bbb2222", tombstone("mods/old.jar", OLD));

		UpdatePlan plan = plan(a, b, Map.of(game("mods/old.jar"), state(OLD)), List.of(), List.of());

		assertDeleted(plan, "mods/old.jar");
	}

	@Test
	void anOriginalGivenBackByLeavingAIsDeletedOnlyWhenItIsExactlyBytesBDeleted() {
		ModpackJsons.ModpackContentFields a = pack("aaa1111", present("mods/shared.jar", KEPT), present("mods/own.jar", KEPT));
		a.list = Set.of(item("mods/shared.jar", KEPT, 8, "mod"), item("mods/own.jar", KEPT, 8, "mod"));
		ModpackJsons.ModpackContentFields b = pack("bbb2222", tombstone("mods/shared.jar", OLD), tombstone("mods/own.jar", NEWLY_DROPPED));
		Map<String, InstanceTree.TrackedFile> originals = Map.of("mods/shared.jar", new InstanceTree.TrackedFile(Root.GAME_DIR, "", "mods/shared.jar", OLD, 8),
				"mods/own.jar", new InstanceTree.TrackedFile(Root.GAME_DIR, "", "mods/own.jar", NEWLY_DROPPED.replace('6', '8'), 8));
		UpdatePlanner.SelectionContext selection = new UpdatePlanner.SelectionContext("aaa1111", a, Map.of(), originals, Set.of(OLD, NEWLY_DROPPED.replace('6', '8')), Set.of());
		Map<FileKey, FileState> files = Map.of(game("mods/shared.jar"), state(KEPT), game("mods/own.jar"), state(KEPT), new FileKey(Root.PROJECTION, "mods/shared.jar"), state(KEPT),
				new FileKey(Root.PROJECTION, "mods/own.jar"), state(KEPT));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(a, b, files, Set.of(), List.of(), List.of(), List.of(), List.of(), selection, new ClientConfigJsons.ClientConfigFieldsV3()));

		assertDeleted(plan, "mods/shared.jar");
		assertEquals(NEWLY_DROPPED.replace('6', '8'), op(plan, "mods/own.jar").orElseThrow().expectedObjectHash());
	}

	@Test
	void aCaseOnlyRenameNeverDeletesTheShippedFileOnAnInsensitiveDisk() {
		ModpackJsons.ModpackContentFields target = pack("abc1234", tombstone("config/Foo.toml", OLD), present("config/foo.toml", OLD));
		target.list = Set.of(item("config/foo.toml", OLD, 8, "config"));
		Map<FileKey, FileState> files = Map.of(game("config/Foo.toml"), state(OLD), game("config/foo.toml"), state(OLD));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(null, target, files, Set.of(), List.of(), List.of(), List.of(), List.of(), null,
				new ClientConfigJsons.ClientConfigFieldsV3(), Map.of(), UpdatePlanner.Owed.NOTHING, Map.of(game("config/Foo.toml"), game("config/foo.toml"))));

		assertTrue(plan.operations().stream().noneMatch(operation -> operation.operation() == OperationType.DELETE));
		assertTrue(plan.preservations().isEmpty());
	}

	@Test
	void pinnedJarsAreKeptOnEverySync() {
		ModpackJsons.ModpackContentFields installed = pack("abc1234", present("mods/sodium.jar", OLD));
		ModpackJsons.ModpackContentFields target = pack("abc1234", tombstone("mods/sodium.jar", OLD));
		List<ModInfo> standard = List.of(new ModInfo("mods/sodium.jar", OLD, 8, "1.0.0", Set.of("sodium"), Set.of()));

		assertTrue(op(plan(null, target, Map.of(game("mods/sodium.jar"), state(OLD)), standard, List.of("sodium")), "mods/sodium.jar").isEmpty());
		assertTrue(op(plan(installed, target, Map.of(game("mods/sodium.jar"), state(OLD)), standard, List.of("sodium")), "mods/sodium.jar").isEmpty());
	}

	@Test
	void crashReportsAndAutoModpackFilesAreNeverCleaned() {
		ModpackJsons.ModpackContentFields target = pack("abc1234", tombstone("crash-reports/crash.txt", OLD), tombstone("mods/automodpack-generated.jar", OLD),
				tombstone("saves/world/level.dat", OLD), tombstone("automodpack-content.json", OLD));

		UpdatePlan plan = plan(null, target, Map.of(game("crash-reports/crash.txt"), state(OLD), game("mods/automodpack-generated.jar"), state(OLD),
				game("saves/world/level.dat"), state(OLD), game("automodpack-content.json"), state(OLD)), List.of(), List.of());

		assertTrue(plan.operations().stream().noneMatch(operation -> operation.root() == Root.GAME_DIR));
		assertTrue(UpdatePlanner.managedCleanupKey("crash-reports/crash.txt").isEmpty());
		assertTrue(UpdatePlanner.managedCleanupKey("mods/automodpack-generated.jar").isEmpty());
	}

	/** A fold onto a path the server ships only in a group this client did not pick names nothing this client has: the stale file is the pack's, so it goes. */
	@Test
	void firstInstallCleansATombstoneThatFoldsOnlyOntoAPathInAnUnpickedGroup() {
		ModpackJsons.ModpackContentFields target = pack("abc1234", tombstone("config/Old.toml", OLD), present("config/old.toml", KEPT));
		target.list = Set.of();

		UpdatePlan plan = plan(null, target, Map.of(game("config/Old.toml"), state(OLD)), List.of(), List.of());

		assertDeleted(plan, "config/Old.toml");
	}

	private static UpdatePlan plan(ModpackJsons.ModpackContentFields installed, ModpackJsons.ModpackContentFields target, Map<FileKey, FileState> files, List<ModInfo> standard,
			List<String> pins) {
		ClientConfigJsons.ClientConfigFieldsV3 config = new ClientConfigJsons.ClientConfigFieldsV3();
		config.pinnedModIds = pins;
		return UpdatePlanner.plan(new UpdatePlanner.Input(installed, target, files, Set.of(), List.of(), standard, List.of(), List.of(), null, config));
	}

	private static ModpackJsons.ModpackContentFields pack(String modpackId, OwnershipLedger.Entry... entries) {
		ModpackJsons.ModpackContentFields pack = manifest(Map.of(), null);
		pack.modpackId = modpackId;
		Map<String, OwnershipLedger.Entry> values = new TreeMap<>();
		for (OwnershipLedger.Entry entry : entries) values.put(entry.logicalPath(), entry);
		pack.ownershipLedger = new OwnershipLedger(modpackId, values).toFields();
		return pack;
	}

	private static OwnershipLedger.Entry present(String path, String hash) {
		return entry(path, OwnershipLedger.Status.PRESENT, hash);
	}

	private static OwnershipLedger.Entry tombstone(String path, String hash) {
		return entry(path, OwnershipLedger.Status.TOMBSTONE, hash);
	}

	private static OwnershipLedger.Entry entry(String path, OwnershipLedger.Status status, String... hashes) {
		NavigableSet<OwnershipLedger.Content> contents = new TreeSet<>(Comparator.comparing(OwnershipLedger.Content::sha1).thenComparingLong(OwnershipLedger.Content::size));
		for (String hash : hashes) contents.add(new OwnershipLedger.Content(hash, 8));
		return new OwnershipLedger.Entry(path, contents, new TreeSet<>(Set.of("main")), status);
	}

	private static FileKey game(String path) {
		return new FileKey(Root.GAME_DIR, path);
	}

	private static FileState state(String hash) {
		return new FileState(hash, 8, true);
	}

	private static Optional<Operation> op(UpdatePlan plan, String path) {
		return plan.operations().stream().filter(operation -> operation.root() == Root.GAME_DIR && operation.relativePath().equals(path)).findFirst();
	}

	private static void assertDeleted(UpdatePlan plan, String path) {
		assertEquals(OperationType.DELETE, op(plan, path).orElseThrow(() -> new AssertionError("no operation for " + path)).operation());
	}
}
