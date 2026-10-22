package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.loader.ModFileCache;
import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.HeldFiles;
import pl.skidam.automodpack_core.update.UpdateCommit;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * #580 end to end: the real planner plans every in-game action on top of the one before, the running game holds a
 * pack config and a file the first update removes, and the restart converges on the result of all of them.
 */
class StackedInGameActionsTest {
	private static final String PACK = "abc1234";
	private static final String MANAGED = "config/managed.toml";
	private static final String KEPT = "config/kept.toml";
	private static final String DROPPED = "config/dropped.toml";
	private static final String ADDED = "config/added.toml";
	private static final SelectionIntent MAIN = new SelectionIntent(Set.of("main"));

	@TempDir
	Path temporaryDirectory;

	@ParameterizedTest
	@ValueSource(ints = {2, 3})
	void stackedUpdatesConvergeOnTheLatestTarget(int actions) throws Exception {
		Instance instance = install();
		SelectedModpackTarget latest = instance.installed;
		for (int action = 1; action <= actions; action++) {
			latest = generation(instance.storage, latest, action);
			assertHeld(instance, instance.update(latest));
			assertArrayEquals(bytes(KEPT, action), Files.readAllBytes(instance.storage.gamePath(KEPT)), "what is not held lands in-game");
		}

		instance.restart();

		assertArrayEquals(bytes(MANAGED, actions), Files.readAllBytes(instance.storage.gamePath(MANAGED)));
		assertArrayEquals(bytes(KEPT, actions), Files.readAllBytes(instance.storage.gamePath(KEPT)));
		assertFalse(Files.exists(instance.storage.gamePath(DROPPED)), "the first update's delete is never left behind");
		assertEquals(latest.packTarget().contentToken(), instance.storage.readActiveState().contentToken);
	}

	/** A file one update added while another file was held is owed to the update stacked on it: one that drops it removes it. */
	@Test
	void aFileAddedWhileAnotherWasHeldIsGoneOnceAStackedUpdateDropsIt() throws Exception {
		Instance instance = install();
		SelectedModpackTarget added = generation(instance.storage, instance.installed, 1, ADDED);
		assertHeld(instance, instance.update(added));
		assertArrayEquals(bytes(ADDED, 1), Files.readAllBytes(instance.storage.gamePath(ADDED)), "the added file lands in-game");
		SelectedModpackTarget dropped = generation(instance.storage, added, 2);
		assertHeld(instance, instance.update(dropped));

		instance.restart();

		assertFalse(Files.exists(instance.storage.gamePath(ADDED)));
		assertArrayEquals(bytes(MANAGED, 2), Files.readAllBytes(instance.storage.gamePath(MANAGED)));
		assertEquals(dropped.packTarget().contentToken(), instance.storage.readActiveState().contentToken);
	}

	/** A removal stacked on updates removes every file they owed. */
	@ParameterizedTest
	@ValueSource(ints = {2, 3})
	void aRemovalStackedOnUpdatesRemovesEverythingTheyOwed(int actions) throws Exception {
		Instance instance = install();
		SelectedModpackTarget latest = instance.installed;
		for (int action = 1; action < actions; action++) {
			latest = generation(instance.storage, latest, action);
			assertHeld(instance, instance.update(latest));
		}
		assertInstanceOf(UpdateCommit.Deferred.class, instance.remove());

		instance.restart();

		for (String path : List.of(MANAGED, KEPT, DROPPED)) assertFalse(Files.exists(instance.storage.gamePath(path)), path);
		assertNull(instance.storage.readActiveState());
	}

	/** A replay that changed loader-scanned files before it stopped short names them, deletes included, for a boot that goes on without relaunching. */
	@Test
	void aReplayThatStopsAfterChangingScannedFilesNamesThem() throws Exception {
		Instance instance = install("mods/a.txt", "mods/old.txt", "mods/z.txt");
		assertHeld(instance, instance.update(generation(instance.storage, instance.installed, 1, "mods/a.txt", "mods/z.txt")));
		Path blocker = instance.storage.gamePath("mods/z.txt");
		Files.delete(blocker);
		Files.createDirectories(blocker.resolve("inside"));

		UpdateCommit boot = new UpdateCommit(instance.storage);
		assertInstanceOf(UpdateCommit.Deferred.class, boot.run(new UpdateCommit.Recover(null)));

		assertEquals(List.of("wrote mods/a.txt", "deleted mods/old.txt"), boot.scannedChanges());
		assertTrue(Files.exists(instance.storage.transactionFile()), "the started journal stays owed");
	}

	/** A started apply no replay can finish - its object is gone for good - stays owed through every boot without a restart loop, and the next sync converges on top of it. */
	@Test
	void aStartedApplyNoReplayCanFinishStaysOwedUntilTheNextSyncConverges() throws Exception {
		Instance instance = install();
		SelectedModpackTarget first = generation(instance.storage, instance.installed, 1);
		assertHeld(instance, instance.update(first));
		Files.delete(instance.storage.objectFile(HashUtils.sha1(bytes(MANAGED, 1))));

		for (int boot = 0; boot < 2; boot++) {
			assertFalse(new BootRecovery(instance.storage).recover().bootAsIs(), "the boot goes on to sync");
			assertTrue(Files.exists(instance.storage.transactionFile()), "the started journal stays owed");
		}
		assertArrayEquals(bytes(KEPT, 1), Files.readAllBytes(instance.storage.gamePath(KEPT)));

		SelectedModpackTarget second = generation(instance.storage, first, 2);
		assertInstanceOf(UpdateCommit.Applied.class, instance.unheld().update(second));

		assertArrayEquals(bytes(MANAGED, 2), Files.readAllBytes(instance.storage.gamePath(MANAGED)));
		assertArrayEquals(bytes(KEPT, 2), Files.readAllBytes(instance.storage.gamePath(KEPT)));
		assertFalse(Files.exists(instance.storage.gamePath(DROPPED)));
		assertFalse(Files.exists(instance.storage.transactionFile()));
		assertEquals(second.packTarget().contentToken(), instance.storage.readActiveState().contentToken);
	}

	private static void assertHeld(Instance instance, UpdateCommit.Outcome outcome) throws Exception {
		assertInstanceOf(UpdateCommit.Deferred.class, outcome, String.valueOf(outcome));
		assertArrayEquals(bytes(MANAGED, 0), Files.readAllBytes(instance.storage.gamePath(MANAGED)), "the held file never moved");
		assertArrayEquals(bytes(DROPPED, 0), Files.readAllBytes(instance.storage.gamePath(DROPPED)), "the held file never moved");
	}

	/** One client instance; in-game, the running game holds two pack files and everything in {@code mods/}. */
	private record Instance(ClientStorage storage, ClientUpdatePlanBuilder builder, UpdateCommit inGame, SelectedModpackTarget installed) {
		UpdateCommit.Outcome update(SelectedModpackTarget target) throws Exception {
			new ClientSelectionStore(storage.selectionFile()).put(PACK, target.selection().intent());
			UpdateTransaction transaction;
			try (FileCache cache = FileCache.open(storage.fileCacheDirectory()); ModFileCache mods = ModFileCache.open(storage.modCacheDirectory())) {
				ClientProjectionView view = ClientProjectionView.observe(storage);
				builder.reconcileEditableState(cache, view, target.flatTarget());
				ClientUpdatePlanBuilder.PreparedPlan prepared = builder.buildPlan(new ClientUpdatePlanBuilder.Input(target, null, new ClientConfigJsons.ClientConfigFieldsV3(), true), view, cache, mods);
				builder.preparePlanObjects(prepared.plan(), target.flatTarget(), ClientProjectionView.observe(storage), cache);
				transaction = UpdateTransaction.create(prepared.plan(), target, prepared.overlayDigest(), prepared.plannedAgainst());
			}
			return inGame.run(new UpdateCommit.Fresh(() -> new UpdateCommit.Built(transaction, target), null));
		}

		UpdateCommit.Outcome remove() throws Exception {
			ClientUpdatePlanBuilder.RemovalPreparation prepared = builder.prepareRemoval();
			new ClientSelectionStore(storage.selectionFile()).remove(PACK);
			UpdateTransaction transaction = UpdateTransaction.createRemoval(prepared.plan(), ClientPlatform.current(), prepared.expectedPriorIntent(), storage.overlayDigest(PACK),
					prepared.plannedAgainst());
			return inGame.run(new UpdateCommit.Fresh(() -> UpdateCommit.Built.of(transaction), null));
		}

		/** The same instance out of game, where nothing is held. */
		Instance unheld() {
			return new Instance(storage, builder, new UpdateCommit(storage), installed);
		}

		/** The next boot: the journal rolls forward with nothing held, and never replans. */
		void restart() throws Exception {
			UpdateCommit boot = new UpdateCommit(storage);
			UpdateCommit.Outcome outcome = boot.run(new UpdateCommit.Recover(cause -> {
				throw new AssertionError("a started journal never replans: " + cause.message());
			}));
			assertInstanceOf(UpdateCommit.Applied.class, outcome, String.valueOf(outcome));
			assertFalse(Files.exists(storage.transactionFile()));
			assertFalse(Files.exists(storage.incomingDirectory()));
			assertFalse(Files.exists(storage.backupDirectory()));
			assertInstanceOf(UpdateCommit.Idle.class, boot.run(new UpdateCommit.Recover(null)));
		}
	}

	private Instance install(String... extra) throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		ClientUpdatePlanBuilder builder = new ClientUpdatePlanBuilder(storage, new ModpackLoaderService() {
			@Override
			public void loadModpack(ModpackLoadRequest request) {}
		}, "fabric");
		SelectedModpackTarget installed = generation(storage, null, 0, extra);
		Instance outOfGame = new Instance(storage, builder, new UpdateCommit(storage), installed);
		assertInstanceOf(UpdateCommit.Applied.class, outOfGame.update(installed));
		Set<Path> held = Set.of(storage.gamePath(MANAGED), storage.gamePath(DROPPED));
		return new Instance(storage, builder, HeldFiles.inGame(storage, path -> held.contains(path) || path.startsWith(storage.modsDirectory())), installed);
	}

	private static byte[] bytes(String path, int version) {
		return (path + " v" + version).getBytes(StandardCharsets.UTF_8);
	}

	/** Generation {@code version}: every pack config at that version plus {@code extra}, the dropped one only in the installed generation. */
	private static SelectedModpackTarget generation(ClientStorage storage, SelectedModpackTarget parent, int version, String... extra) throws Exception {
		List<String> paths = new ArrayList<>(List.of(MANAGED, KEPT));
		paths.addAll(List.of(extra));
		if (version == 0) paths.add(DROPPED);
		Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields> files = new LinkedHashMap<>();
		for (String path : paths) {
			byte[] content = bytes(path, version);
			ClientObjectStore.storeObject(storage, HashUtils.sha1(content), content);
			files.put(path, new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(content.length), ModpackPathPolicy.typeForPath(path), false, HashUtils.sha1(content), "0"));
		}
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = PACK;
		fields.modpackName = "Test";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = true;
		group.defaultSelected = true;
		group.files = files;
		fields.categories = Map.of("General", Map.of("main", group));
		GroupManifest manifest = GroupManifestValidator.validate(fields);
		PackDocument document = parent == null ? TestPacks.document(manifest) : TestPacks.document(manifest, parent.document().ownershipLedger(), TestPacks.CREATED.plusSeconds(version));
		TestPacks.stageGeneration(storage, document);
		return SelectedModpackTarget.prepare(document, parent == null ? null : MAIN, MAIN, ClientPlatform.current());
	}
}
