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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
	private static final String FABRIC = "net.fabricmc.fabric-loader";
	private static final String INSTALLED_LOADER = "0.16.0";
	private static final SelectionIntent MAIN = new SelectionIntent(Set.of("main"));

	@TempDir
	Path temporaryDirectory;

	@ParameterizedTest
	@ValueSource(ints = {2, 3})
	void stackedUpdatesConvergeOnTheLatestTarget(int actions) throws Exception {
		Instance instance = install(temporaryDirectory);
		SelectedModpackTarget latest = instance.installed;
		for (int action = 1; action <= actions; action++) {
			latest = generation(instance.storage, latest, action, INSTALLED_LOADER);
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
		Instance instance = install(temporaryDirectory);
		SelectedModpackTarget added = generation(instance.storage, instance.installed, 1, INSTALLED_LOADER, ADDED);
		assertHeld(instance, instance.update(added));
		assertArrayEquals(bytes(ADDED, 1), Files.readAllBytes(instance.storage.gamePath(ADDED)), "the added file lands in-game");
		SelectedModpackTarget dropped = generation(instance.storage, added, 2, INSTALLED_LOADER);
		assertHeld(instance, instance.update(dropped));

		instance.restart();

		assertFalse(Files.exists(instance.storage.gamePath(ADDED)));
		assertArrayEquals(bytes(MANAGED, 2), Files.readAllBytes(instance.storage.gamePath(MANAGED)));
		assertEquals(dropped.packTarget().contentToken(), instance.storage.readActiveState().contentToken);
	}

	/** A removal stacked on updates removes every file they owed, and keeps the loader the last of them switches to, as it would had they finished. */
	@ParameterizedTest
	@ValueSource(ints = {2, 3})
	void aRemovalStackedOnUpdatesRemovesEverythingTheyOwed(int actions) throws Exception {
		Instance instance = install(temporaryDirectory);
		SelectedModpackTarget latest = instance.installed;
		for (int action = 1; action < actions; action++) {
			latest = generation(instance.storage, latest, action, "0.16." + (4 + action));
			assertHeld(instance, instance.update(latest));
		}
		assertInstanceOf(UpdateCommit.Deferred.class, instance.remove());

		instance.restart();

		for (String path : List.of(MANAGED, KEPT, DROPPED)) assertFalse(Files.exists(instance.storage.gamePath(path)), path);
		assertNull(instance.storage.readActiveState());
		assertEquals("0.16." + (3 + actions), instance.launcherVersion(FABRIC));
	}

	/**
	 * The switch lands with the files it belongs to, and every action plans on top of the result of the ones before it,
	 * so the loader the instance ends on never depends on whether a file was held: "-" advertises no versions, and "@1.21.1"
	 * only the game version.
	 */
	@ParameterizedTest
	@CsvSource({"0.16.5 0.16.5, 0.16.5", "0.16.5 0.17.0, 0.17.0", "0.16.5 - 0.17.0, 0.17.0", "0.16.5 -, 0.16.5", "0.16.5 remove, 0.16.5", "0.16.5 - remove, 0.16.5", "0.16.5 @1.21.1, 0.16.5",
			"0.16.5 @1.21.1 remove, 0.16.5"})
	void stackedUpdatesEndOnTheLastLoaderSwitch(String actions, String loader) throws Exception {
		List<String> steps = Arrays.asList(actions.split(" "));
		assertEquals(loader, endLoader(install(temporaryDirectory.resolve("finished")).unheld(), steps, false), "every action finished in-game");
		assertEquals(loader, endLoader(install(temporaryDirectory.resolve("held")), steps, true), "every action stayed owed until the restart");
	}

	private static String endLoader(Instance instance, List<String> steps, boolean held) throws Exception {
		SelectedModpackTarget latest = instance.installed;
		for (int action = 1; action <= steps.size(); action++) {
			String step = steps.get(action - 1);
			UpdateCommit.Outcome outcome;
			if (step.equals("remove")) {
				outcome = instance.remove();
			} else {
				latest = generation(instance.storage, latest, action, step.equals("-") ? null : step);
				outcome = instance.update(latest);
			}
			assertEquals(held ? UpdateCommit.Deferred.class : UpdateCommit.Applied.class, outcome.getClass(), String.valueOf(outcome));
			if (held) assertEquals(INSTALLED_LOADER, instance.launcherVersion(FABRIC), "the switch waits for the held files");
		}
		if (held) assertTrue(instance.restart().scannedFilesChanged(), "a switch landing at boot relaunches the game");
		return instance.launcherVersion(FABRIC);
	}

	/** Offline, an action stacked on an owed game and loader switch carries it on as it was planned, with no version lookup, and ends on it. */
	@ParameterizedTest
	@ValueSource(strings = {"remove", "-"})
	void anOfflineActionCarriesAnOwedSwitchOnAsPlanned(String step) throws Exception {
		Instance instance = install(temporaryDirectory);
		SelectedModpackTarget switching = generation(instance.storage, instance.installed, 1, "0.16.5@1.21.4");
		assertHeld(instance, instance.update(switching));
		Instance offline = instance.offline();

		assertInstanceOf(UpdateCommit.Deferred.class, step.equals("remove") ? offline.remove() : offline.update(generation(instance.storage, switching, 2, null)));
		instance.restart();

		assertEquals("0.16.5", instance.launcherVersion(FABRIC));
		assertEquals("1.21.4", instance.launcherVersion("net.minecraft"));
		assertEquals(step.equals("remove"), instance.storage.readActiveState() == null);
	}

	/** A replay that changed loader-scanned files before it stopped short names them, deletes included, for a boot that goes on without relaunching. */
	@Test
	void aReplayThatStopsAfterChangingScannedFilesNamesThem() throws Exception {
		Instance instance = install(temporaryDirectory, "mods/a.txt", "mods/old.txt", "mods/z.txt");
		assertHeld(instance, instance.update(generation(instance.storage, instance.installed, 1, INSTALLED_LOADER, "mods/a.txt", "mods/z.txt")));
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
		Instance instance = install(temporaryDirectory);
		SelectedModpackTarget first = generation(instance.storage, instance.installed, 1, INSTALLED_LOADER);
		assertHeld(instance, instance.update(first));
		Files.delete(instance.storage.objectFile(HashUtils.sha1(bytes(MANAGED, 1))));

		for (int boot = 0; boot < 2; boot++) {
			assertFalse(new BootRecovery(instance.storage).recover().bootAsIs(), "the boot goes on to sync");
			assertTrue(Files.exists(instance.storage.transactionFile()), "the started journal stays owed");
		}
		assertArrayEquals(bytes(KEPT, 1), Files.readAllBytes(instance.storage.gamePath(KEPT)));

		SelectedModpackTarget second = generation(instance.storage, first, 2, INSTALLED_LOADER);
		assertInstanceOf(UpdateCommit.Applied.class, instance.unheld().update(second));

		assertArrayEquals(bytes(MANAGED, 2), Files.readAllBytes(instance.storage.gamePath(MANAGED)));
		assertArrayEquals(bytes(KEPT, 2), Files.readAllBytes(instance.storage.gamePath(KEPT)));
		assertFalse(Files.exists(instance.storage.gamePath(DROPPED)));
		assertFalse(Files.exists(instance.storage.transactionFile()));
		assertEquals(second.packTarget().contentToken(), instance.storage.readActiveState().contentToken);
	}

	/** Launcher metadata that vanishes after the pre-write gate fails the replay past the swap; it stays owed and lands whole, switch included, once the metadata is back. */
	@Test
	void aSwitchWhoseLauncherVanishedAfterTheGateFinishesOnceItIsBack() throws Exception {
		Instance instance = install(temporaryDirectory);
		SelectedModpackTarget switching = generation(instance.storage, instance.installed, 1, "0.16.5");
		assertHeld(instance, instance.update(switching));
		byte[] launcher = Files.readAllBytes(mmcPack(instance.storage));
		Files.delete(mmcPack(instance.storage));

		for (int boot = 0; boot < 2; boot++) {
			assertFalse(new BootRecovery(instance.storage).recover().bootAsIs());
			assertTrue(Files.exists(instance.storage.transactionFile()), "the started journal stays owed");
		}
		assertArrayEquals(bytes(MANAGED, 1), Files.readAllBytes(instance.storage.gamePath(MANAGED)), "the files landed");
		assertEquals(instance.installed.packTarget().contentToken(), instance.storage.readActiveState().contentToken, "but the update did not finalize");

		Files.write(mmcPack(instance.storage), launcher);
		assertTrue(instance.restart().scannedFilesChanged());

		assertEquals("0.16.5", instance.launcherVersion(FABRIC));
		assertEquals(switching.packTarget().contentToken(), instance.storage.readActiveState().contentToken);
	}

	/** Launcher metadata gone or unreadable before the first write replans the switch rather than land files the launcher cannot follow. */
	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void aSwitchWhoseLauncherIsUnusableBeforeTheFirstWriteTouchesNothing(boolean deleted) throws Exception {
		Instance instance = install(temporaryDirectory).unheld();
		UpdateTransaction switching = instance.plan(generation(instance.storage, instance.installed, 1, "0.16.5"));
		if (deleted) Files.delete(mmcPack(instance.storage));
		else Files.writeString(mmcPack(instance.storage), "{");

		UpdateCommit.Outcome outcome = instance.inGame.run(new UpdateCommit.Fresh(() -> UpdateCommit.Built.of(switching), null));

		assertInstanceOf(UpdateCommit.ReplanRequired.class, outcome, String.valueOf(outcome));
		for (String path : List.of(MANAGED, KEPT, DROPPED)) assertArrayEquals(bytes(path, 0), Files.readAllBytes(instance.storage.gamePath(path)), path);
		assertEquals(instance.installed.packTarget().contentToken(), instance.storage.readActiveState().contentToken);
	}

	private static void assertHeld(Instance instance, UpdateCommit.Outcome outcome) throws Exception {
		assertInstanceOf(UpdateCommit.Deferred.class, outcome, String.valueOf(outcome));
		assertArrayEquals(bytes(MANAGED, 0), Files.readAllBytes(instance.storage.gamePath(MANAGED)), "the held file never moved");
		assertArrayEquals(bytes(DROPPED, 0), Files.readAllBytes(instance.storage.gamePath(DROPPED)), "the held file never moved");
	}

	/** One client instance on a Prism launcher; in-game, the running game holds two pack files and everything in {@code mods/}. */
	private record Instance(ClientStorage storage, ClientUpdatePlanBuilder builder, UpdateCommit inGame, SelectedModpackTarget installed) {
		UpdateCommit.Outcome update(SelectedModpackTarget target) throws Exception {
			UpdateTransaction transaction = plan(target);
			return inGame.run(new UpdateCommit.Fresh(() -> new UpdateCommit.Built(transaction, target), null));
		}

		UpdateTransaction plan(SelectedModpackTarget target) throws Exception {
			new ClientSelectionStore(storage.selectionFile()).put(PACK, target.selection().intent());
			try (FileCache cache = FileCache.open(storage.fileCacheDirectory()); ModFileCache mods = ModFileCache.open(storage.modCacheDirectory())) {
				ClientProjectionView view = ClientProjectionView.observe(storage);
				builder.reconcileEditableState(cache, view, target.flatTarget());
				ClientUpdatePlanBuilder.PreparedPlan prepared = builder.buildPlan(new ClientUpdatePlanBuilder.Input(target, null, new ClientConfigJsons.ClientConfigFieldsV3(), true), view, cache, mods);
				builder.preparePlanObjects(prepared.plan(), target.flatTarget(), ClientProjectionView.observe(storage), cache);
				return UpdateTransaction.create(prepared.plan(), target, prepared.overlayDigest(), prepared.plannedAgainst());
			}
		}

		/** The same instance out of game, where nothing is held. */
		Instance unheld() {
			return new Instance(storage, builder, new UpdateCommit(storage), installed);
		}

		UpdateCommit.Outcome remove() throws Exception {
			ClientUpdatePlanBuilder.RemovalPreparation prepared = builder.prepareRemoval();
			new ClientSelectionStore(storage.selectionFile()).remove(PACK);
			UpdateTransaction transaction = UpdateTransaction.createRemoval(prepared.plan(), ClientPlatform.current(), prepared.expectedPriorIntent(), storage.overlayDigest(PACK),
					prepared.plannedAgainst());
			return inGame.run(new UpdateCommit.Fresh(() -> UpdateCommit.Built.of(transaction), null));
		}

		/** The next boot: the journal rolls forward with nothing held, and never replans. */
		UpdateCommit.Applied restart() throws Exception {
			UpdateCommit boot = new UpdateCommit(storage);
			UpdateCommit.Outcome outcome = boot.run(new UpdateCommit.Recover(cause -> {
				throw new AssertionError("a started journal never replans: " + cause.message());
			}));
			assertInstanceOf(UpdateCommit.Applied.class, outcome, String.valueOf(outcome));
			assertFalse(Files.exists(storage.transactionFile()));
			assertFalse(Files.exists(storage.incomingDirectory()));
			assertFalse(Files.exists(storage.backupDirectory()));
			assertInstanceOf(UpdateCommit.Idle.class, boot.run(new UpdateCommit.Recover(null)));
			return (UpdateCommit.Applied) outcome;
		}

		String launcherVersion(String uid) throws Exception {
			for (JsonElement component : JsonParser.parseString(Files.readString(mmcPack(storage))).getAsJsonObject().getAsJsonArray("components")) {
				JsonObject object = component.getAsJsonObject();
				if (object.get("uid").getAsString().equals(uid)) return object.get("version").getAsString();
			}
			return null;
		}

		/** The same instance with Prism meta out of reach: any version lookup fails the test. */
		Instance offline() {
			return new Instance(storage, StackedInGameActionsTest.builder(storage, (uid, version) -> {
				throw new AssertionError("no network: " + uid + " " + version);
			}), inGame, installed);
		}
	}

	private static Instance install(Path root, String... extra) throws Exception {
		ClientStorage storage = TestDataRoot.open(root.resolve("game"), root.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		Files.writeString(mmcPack(storage), "{\"formatVersion\":1,\"components\":[{\"uid\":\"net.minecraft\",\"version\":\"1.21.1\"},{\"uid\":\"" + FABRIC + "\",\"version\":\""
				+ INSTALLED_LOADER + "\",\"cachedVersion\":\"" + INSTALLED_LOADER + "\"}]}");
		ClientUpdatePlanBuilder builder = builder(storage, (uid, version) -> true);
		SelectedModpackTarget installed = generation(storage, null, 0, INSTALLED_LOADER, extra);
		Instance outOfGame = new Instance(storage, builder, new UpdateCommit(storage), installed);
		assertInstanceOf(UpdateCommit.Applied.class, outOfGame.update(installed));
		Set<Path> held = Set.of(storage.gamePath(MANAGED), storage.gamePath(DROPPED));
		return new Instance(storage, builder, HeldFiles.inGame(storage, path -> held.contains(path) || path.startsWith(storage.modsDirectory())), installed);
	}

	private static ClientUpdatePlanBuilder builder(ClientStorage storage, BiPredicate<String, String> versionKnown) {
		return new ClientUpdatePlanBuilder(storage, new ModpackLoaderService() {
			@Override
			public void loadModpack(ModpackLoadRequest request) {}
		}, "fabric", versionKnown);
	}

	private static Path mmcPack(ClientStorage storage) {
		return storage.gameDirectory().toAbsolutePath().getParent().resolve("mmc-pack.json");
	}

	private static byte[] bytes(String path, int version) {
		return (path + " v" + version).getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Generation {@code version}: every pack config at that version plus {@code extra}, the dropped one only in the installed
	 * generation. It advertises fabric at {@code versions}, {@code loaderVersion[@mcVersion]} with Minecraft 1.21.1 by default
	 * and a blank part left blank; null advertises no versions.
	 */
	private static SelectedModpackTarget generation(ClientStorage storage, SelectedModpackTarget parent, int version, String versions, String... extra) throws Exception {
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
		String[] parts = versions == null ? new String[]{"", ""} : (versions.contains("@") ? versions : versions + "@1.21.1").split("@", -1);
		fields.loader = versions == null ? "" : "fabric";
		fields.loaderVersion = parts[0];
		fields.mcVersion = parts[1];
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
