package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * #580 and its class: every apply lands what it can and rolls forward, a held file stays owed, any number of actions in
 * one game process stack on each other, and the next boot converges on the latest one.
 */
class BestEffortApplyTest {
	private static final String PACK = "abc1234";
	private static final String MOD = "mods/m.jar";
	private static final String MANAGED = "config/managed.toml";
	private static final String EDITABLE = "config/editable.toml";
	private static final String CONFIG = "config/jeed-client.toml";
	private static final byte[] OLD_JAR = "old jar".getBytes(StandardCharsets.UTF_8);
	private static final byte[] LF = "a = 1\nb = 2\n".getBytes(StandardCharsets.UTF_8);
	private static final byte[] CRLF = "a = 1\r\nb = 2\r\n".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path temporaryDirectory;

	/** rc.4 and rc.5 wrote the live config in-game, then deferred the swap; the running mod rewrote the file before recovery (#580 failure 2). */
	@ParameterizedTest
	@ValueSource(strings = {"rc.4", "rc.5"})
	void aReleaseCandidateDeferredPublicationRollsForwardAndKeepsTheModsRewrite(String release) throws Exception {
		Fixture fixture = twoGenerations();
		Files.createDirectories(fixture.storage.incomingDirectory());
		OverlayTombstoneCommitTest.installFixture(fixture.storage, release + "-deferred-publication.json");
		write(fixture.storage.gamePath(CONFIG), CRLF);

		UpdateCommit.Outcome recovered = UpdateTestFixtures.commits(fixture.storage).run(new UpdateCommit.Recover(cause -> {
			throw new AssertionError("a started journal never replans: " + cause.message());
		}));

		assertInstanceOf(UpdateCommit.Applied.class, recovered, String.valueOf(recovered));
		assertArrayEquals(LF, Files.readAllBytes(fixture.storage.activePath(CONFIG)));
		assertFalse(Files.exists(fixture.storage.activePath("mods/old.jar")));
		assertArrayEquals(CRLF, Files.readAllBytes(fixture.storage.gamePath(CONFIG)), "the write after the landed operation is the mod's, and reconciliation owns it");
		assertTrue(InstanceTree.read(fixture.storage, ClientStateJournal.open(fixture.storage).head().treeSha1()).files().stream()
				.anyMatch(file -> file.root() == Root.GAME_DIR && file.path().equals(CONFIG) && file.sha1().equals(HashUtils.sha1(CRLF))));
		assertInstanceOf(UpdateCommit.Idle.class, UpdateTestFixtures.commits(fixture.storage).run(new UpdateCommit.Recover(null)));
	}

	/** #580 failure 2 on this engine: a held projection keeps the swap owed, the mod rewrites its landed config, and the restart neither replans nor loops. */
	@Test
	void aHeldProjectionFinishesAtTheNextBootWithoutReplanningAModsRewrite() throws Exception {
		Fixture fixture = twoGenerations();
		UpdateCommit inGame = HeldFiles.inGame(fixture.storage, path -> path.equals(fixture.storage.activeDirectory()));

		UpdateCommit.Outcome deferred = inGame.run(new UpdateCommit.Fresh(() -> built(fixture.storage, fixture.plan, fixture.target), null));

		assertInstanceOf(UpdateCommit.Deferred.class, deferred, String.valueOf(deferred));
		assertArrayEquals(LF, Files.readAllBytes(fixture.storage.gamePath(CONFIG)), "the live write lands in-game");
		UpdateTransaction owed = UpdateTransaction.read(fixture.storage.transactionFile());
		assertTrue(owed.plan().operations().stream().allMatch(operation -> operation.root() == Root.PROJECTION), "only the projection is still owed");
		write(fixture.storage.gamePath(CONFIG), CRLF);

		assertInstanceOf(UpdateCommit.Applied.class, UpdateTestFixtures.commits(fixture.storage).run(new UpdateCommit.Recover(cause -> {
			throw new AssertionError("a started journal never replans: " + cause.message());
		})));
		assertArrayEquals(CRLF, Files.readAllBytes(fixture.storage.gamePath(CONFIG)));
		assertArrayEquals(LF, Files.readAllBytes(fixture.storage.activePath(CONFIG)));
		assertEquals(fixture.target.packTarget().contentToken(), fixture.storage.readActiveState().contentToken);
		assertInstanceOf(UpdateCommit.Idle.class, UpdateTestFixtures.commits(fixture.storage).run(new UpdateCommit.Recover(null)));
	}

	/** Crash at every durable step, then again at the same step of the replay: every replay converges on one end state. */
	@ParameterizedTest
	@EnumSource(UpdateCommit.Step.class)
	void aCrashAtAnyStepReplaysToTheSameEndState(UpdateCommit.Step step) throws Exception {
		Fixture fixture = twoGenerations();
		write(fixture.storage.gamePath(CONFIG), CRLF);
		fixture.storage.writeOverlayState(PACK, Set.of("config/stale.toml"));
		UpdateCommit crashing = new UpdateCommit(fixture.storage, crashingAt(step));

		assertThrows(Crash.class, () -> crashing.run(new UpdateCommit.Fresh(() -> built(fixture.storage, fixture.plan, fixture.target), null)));
		// A replay passes every step again except the ones whose work it finds done: the journal, the swap, and the COMMITTED marker.
		if (Set.of(UpdateCommit.Step.LIVE_APPLIED, UpdateCommit.Step.PROJECTION_BUILT, UpdateCommit.Step.FINALIZED).contains(step))
			assertThrows(Crash.class, () -> crashing.run(new UpdateCommit.Recover(null)), "the replay passes the same step again");

		UpdateCommit.Outcome recovered = UpdateTestFixtures.commits(fixture.storage).run(new UpdateCommit.Recover(null));

		assertInstanceOf(UpdateCommit.Applied.class, recovered, String.valueOf(recovered));
		assertFalse(Files.exists(fixture.storage.transactionFile()));
		assertFalse(Files.exists(fixture.storage.incomingDirectory()));
		assertFalse(Files.exists(fixture.storage.backupDirectory()));
		assertArrayEquals(LF, Files.readAllBytes(fixture.storage.gamePath(CONFIG)));
		assertArrayEquals(LF, Files.readAllBytes(fixture.storage.activePath(CONFIG)));
		assertFalse(Files.exists(fixture.storage.activePath("mods/old.jar")));
		assertEquals(fixture.target.packTarget().contentToken(), fixture.storage.readActiveState().contentToken);
		assertTrue(fixture.storage.readOverlayState(PACK).deletedPaths.isEmpty());
		assertInstanceOf(UpdateCommit.Idle.class, UpdateTestFixtures.commits(fixture.storage).run(new UpdateCommit.Recover(null)));
	}

	/**
	 * Windows in-game: the running game holds a pack-owned config, so every action leaves it owed while the rest lands.
	 * Each later action plans on top of the last; the editable file the first one landed is then the player's, and the
	 * restart converges on the latest target.
	 */
	@ParameterizedTest
	@ValueSource(ints = {2, 3})
	void consecutiveInGameActionsWithAHeldFileConvergeOnTheLatestTargetAtRestart(int actions) throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		Generation current = install(storage, 0);
		UpdateCommit inGame = HeldFiles.inGame(storage, path -> path.equals(storage.gamePath(MANAGED)));
		byte[] edit = "player edit".getBytes(StandardCharsets.UTF_8);
		for (int action = 1; action <= actions; action++) {
			Generation next = generation(storage, current, action, action == 1 ? action : 1);
			UpdatePlan plan = stackedPlan(storage, current, next);
			UpdateCommit.Outcome outcome = inGame.run(new UpdateCommit.Fresh(() -> built(storage, plan, next.target), null));

			assertInstanceOf(UpdateCommit.Deferred.class, outcome, String.valueOf(outcome));
			assertArrayEquals(bytes(MANAGED, 0), Files.readAllBytes(storage.gamePath(MANAGED)), "the held file never moved");
			assertArrayEquals(bytes(MOD, 0), Files.readAllBytes(storage.activePath(MOD)), "the running game keeps its projection");
			UpdateTransaction owed = UpdateTransaction.read(storage.transactionFile());
			assertEquals(List.of(MANAGED), owed.plan().operations().stream().filter(operation -> operation.root() != Root.PROJECTION).map(Operation::relativePath).toList());
			assertTrue(ClientProjectionView.mutationStarted(storage, owed));
			assertFalse(ClientProjectionView.publicationStarted(storage, owed));
			if (action == 1) {
				assertArrayEquals(bytes(EDITABLE, 1), Files.readAllBytes(storage.gamePath(EDITABLE)));
				write(storage.gamePath(EDITABLE), edit);
			}
			current = next;
		}

		UpdateCommit.Outcome restart = UpdateTestFixtures.commits(storage).run(new UpdateCommit.Recover(cause -> {
			throw new AssertionError("a started journal never replans: " + cause.message());
		}));

		assertInstanceOf(UpdateCommit.Applied.class, restart, String.valueOf(restart));
		assertArrayEquals(bytes(MANAGED, actions), Files.readAllBytes(storage.gamePath(MANAGED)));
		assertArrayEquals(edit, Files.readAllBytes(storage.gamePath(EDITABLE)), "an editable file changed after its write landed stays the player's");
		assertArrayEquals(bytes(MOD, actions), Files.readAllBytes(storage.activePath(MOD)));
		assertEquals(current.target.packTarget().contentToken(), storage.readActiveState().contentToken);
		assertFalse(Files.exists(storage.incomingDirectory()));
		assertFalse(Files.exists(storage.backupDirectory()));
		assertInstanceOf(UpdateCommit.Idle.class, UpdateTestFixtures.commits(storage).run(new UpdateCommit.Recover(null)));
	}

	/** A held file a mod rewrote before its operation ever landed is still owed: the target wins, editable or not, and the rewrite stays in history. */
	@ParameterizedTest
	@ValueSource(strings = {MANAGED, EDITABLE})
	void aHeldFileRewrittenBeforeItsWriteLandedStillGetsTheTarget(String held) throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		Generation installed = install(storage, 0);
		Generation next = generation(storage, installed, 1, 1);
		UpdatePlan plan = stackedPlan(storage, installed, next);
		UpdateCommit inGame = HeldFiles.inGame(storage, path -> path.equals(storage.gamePath(held)));
		assertInstanceOf(UpdateCommit.Deferred.class, inGame.run(new UpdateCommit.Fresh(() -> built(storage, plan, next.target), null)));
		byte[] rewrite = "rewritten while held".getBytes(StandardCharsets.UTF_8);
		write(storage.gamePath(held), rewrite);

		assertInstanceOf(UpdateCommit.Applied.class, UpdateTestFixtures.commits(storage).run(new UpdateCommit.Recover(null)));

		assertArrayEquals(bytes(held, 1), Files.readAllBytes(storage.gamePath(held)));
		assertTrue(ClientStateJournal.open(storage).entries().stream().map(entry -> {
			try {
				return InstanceTree.read(storage, entry.treeSha1());
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}).anyMatch(tree -> tree.files().stream().anyMatch(file -> file.path().equals(held) && file.sha1().equals(HashUtils.sha1(rewrite)))), "the rewrite is checkpointed before it is replaced");
	}

	/** A fresh action replaces an owed journal mid-swap: the game keeps a projection, and the new action publishes its own. */
	@Test
	void anActionReplacingAJournalStoppedMidSwapStartsFromALoadableProjection() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		Generation installed = install(storage, 0);
		Generation first = generation(storage, installed, 1, 1);
		UpdatePlan firstPlan = stackedPlan(storage, installed, first);
		assertThrows(Crash.class, () -> new UpdateCommit(storage, crashingAt(UpdateCommit.Step.PROJECTION_BUILT))
				.run(new UpdateCommit.Fresh(() -> built(storage, firstPlan, first.target), null)));
		// The swap moved active aside and stopped before incoming moved in.
		Files.move(storage.activeDirectory(), storage.backupDirectory());

		Generation second = generation(storage, first, 2, 2);
		UpdatePlan secondPlan = stackedPlan(storage, first, second);
		assertInstanceOf(UpdateCommit.Applied.class, UpdateTestFixtures.commits(storage).run(new UpdateCommit.Fresh(() -> built(storage, secondPlan, second.target), null)));

		assertArrayEquals(bytes(MOD, 2), Files.readAllBytes(storage.activePath(MOD)));
		assertArrayEquals(bytes(MANAGED, 2), Files.readAllBytes(storage.gamePath(MANAGED)));
		assertEquals(second.target.packTarget().contentToken(), storage.readActiveState().contentToken);
	}

	/** A swap whose second move fails puts the projection it moved aside back, so the game is never left without one. */
	@Test
	void aSwapThatCannotMoveIncomingInPutsActiveBack() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		Generation installed = install(storage, 0);
		Generation next = generation(storage, installed, 1, 1);
		UpdateTransaction transaction = UpdateTestFixtures.createTransaction(storage, stackedPlan(storage, installed, next), next.target);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			assertThrows(IOException.class, () -> new ProjectionPublication(storage, cache).swap(transaction), "there is no incoming tree to move in");
		}

		assertArrayEquals(bytes(MOD, 0), Files.readAllBytes(storage.activePath(MOD)));
		assertFalse(Files.exists(storage.backupDirectory()));
	}

	/** No markers: the journals this engine writes carry only the fields rc.5 already reads, so a downgrade replays them. */
	@Test
	void anOwedJournalSpeaksOnlyTheReleaseCandidateSchema() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		Generation installed = install(storage, 0);
		Generation next = generation(storage, installed, 1, 1);
		UpdatePlan plan = stackedPlan(storage, installed, next);
		HeldFiles.inGame(storage, path -> path.equals(storage.gamePath(MANAGED))).run(new UpdateCommit.Fresh(() -> built(storage, plan, next.target), null));
		Set<String> written = keys(JsonParser.parseString(Files.readString(storage.transactionFile())), "");
		Set<String> released = new TreeSet<>();
		for (String release : List.of("rc.5-deferred-publication.json", "rc.5-tombstone-delete-failed.json"))
			try (var fixture = BestEffortApplyTest.class.getResourceAsStream("journals/" + release)) {
				released.addAll(keys(JsonParser.parseString(new String(fixture.readAllBytes(), StandardCharsets.UTF_8)), ""));
			}
		// rc.5's UpdateTransaction declares both result fields; its fixtures only happen to leave them null.
		released.addAll(Set.of("resultOperation", "resultPath"));

		Set<String> unknown = new TreeSet<>(written);
		unknown.removeAll(released);
		assertTrue(unknown.isEmpty(), "keys rc.5 never wrote: " + unknown);
		UpdateTransaction owed = UpdateTransaction.read(storage.transactionFile());
		assertTrue(Set.of(UpdateTransaction.Phase.PLANNED, UpdateTransaction.Phase.PREPARING, UpdateTransaction.Phase.DEFERRED).contains(owed.phase));
		assertEquals(UpdateTransaction.Status.DEFERRED_LOCKED, owed.resultStatus);
	}

	private static Set<String> keys(JsonElement element, String prefix) {
		Set<String> keys = new TreeSet<>();
		if (element.isJsonObject()) {
			JsonObject object = element.getAsJsonObject();
			for (var entry : object.entrySet()) {
				String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
				keys.add(key);
				keys.addAll(keys(entry.getValue(), key));
			}
		} else if (element.isJsonArray()) {
			for (JsonElement item : element.getAsJsonArray()) keys.addAll(keys(item, prefix + "[]"));
		}
		return keys;
	}

	private static final class Crash extends Error {}

	private static UpdateCommit.Probe crashingAt(UpdateCommit.Step step) {
		return new UpdateCommit.Probe() {
			@Override
			public void reached(UpdateCommit.Step reached) {
				if (reached == step) throw new Crash();
			}
		};
	}

	private static UpdateCommit.Built built(ClientStorage storage, UpdatePlan plan, SelectedModpackTarget target) throws IOException {
		return new UpdateCommit.Built(UpdateTestFixtures.createTransaction(storage, plan, target), target);
	}

	private record Generation(SelectedModpackTarget target, Map<String, byte[]> files) {}

	/** Version {@code v} of every pack file; the editable file only changes in the first update so a later one has nothing to say about it. */
	private static byte[] bytes(String path, int version) {
		return (path + " v" + version).getBytes(StandardCharsets.UTF_8);
	}

	private static Generation install(ClientStorage storage, int version) throws Exception {
		Generation installed = generation(storage, null, version, version);
		UpdatePlan plan = stackedPlan(storage, null, installed);
		assertInstanceOf(UpdateCommit.Applied.class, UpdateTestFixtures.commits(storage).run(new UpdateCommit.Fresh(() -> built(storage, plan, installed.target), null)));
		return installed;
	}

	private static Generation generation(ClientStorage storage, Generation parent, int version, int editableVersion) throws Exception {
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put(MOD, bytes(MOD, version));
		files.put(MANAGED, bytes(MANAGED, version));
		files.put(EDITABLE, bytes(EDITABLE, editableVersion));
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = PACK;
		fields.modpackName = "Test";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = true;
		group.defaultSelected = true;
		Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields> groupFiles = new LinkedHashMap<>();
		for (var entry : files.entrySet()) {
			String hash = OverlayTombstoneCommitTest.store(storage, entry.getValue());
			groupFiles.put(entry.getKey(), new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(entry.getValue().length),
					entry.getKey().startsWith("mods/") ? "mod" : "config", entry.getKey().equals(EDITABLE), hash, "0"));
		}
		group.files = groupFiles;
		fields.categories = Map.of("General", Map.of("main", group));
		GroupManifest manifest = GroupManifestValidator.validate(fields);
		SelectionIntent main = new SelectionIntent(Set.of("main"));
		PackDocument document = parent == null
				? TestPacks.document(manifest)
				: PackDocument.create(manifest, TestPacks.policySha1(manifest), Instant.parse("2026-01-01T00:00:00Z").plusSeconds(version), parent.target.document().ownershipLedger());
		TestPacks.stageGeneration(storage, document);
		return new Generation(SelectedModpackTarget.prepare(document, parent == null ? null : main, main, ClientPlatform.LINUX), files);
	}

	/**
	 * The plan the planner builds on top of the pending one: the projection for the whole target, and a game-directory
	 * write for each pack config the stacked previous target did not already ship, baselined on the live bytes.
	 */
	private static UpdatePlan stackedPlan(ClientStorage storage, Generation previous, Generation next) throws Exception {
		List<Operation> operations = new ArrayList<>();
		List<ProjectedFile> finalState = new ArrayList<>();
		for (var entry : next.files.entrySet()) {
			String path = entry.getKey();
			String hash = HashUtils.sha1(entry.getValue());
			long size = entry.getValue().length;
			finalState.add(new ProjectedFile(Root.PROJECTION, path, true, hash, size));
			if (previous == null || !Arrays.equals(previous.files.get(path), entry.getValue()))
				operations.add(new Operation(Root.PROJECTION, path, OperationType.INSTALL_OBJECT, hash, size, null));
			if (path.startsWith("mods/")) continue;
			finalState.add(new ProjectedFile(Root.GAME_DIR, path, true, hash, size));
			Path live = storage.gamePath(path);
			byte[] observed = Files.exists(live) ? Files.readAllBytes(live) : null;
			boolean shipped = previous != null && Arrays.equals(previous.files.get(path), entry.getValue());
			if (shipped || Arrays.equals(observed, entry.getValue())) continue;
			operations.add(new Operation(Root.GAME_DIR, path, OperationType.INSTALL_OBJECT, hash, size, observed == null ? null : HashUtils.sha1(observed)));
		}
		operations.sort(Operation.ORDER);
		finalState.sort(Comparator.comparing((ProjectedFile row) -> row.root().ordinal()).thenComparing(ProjectedFile::relativePath));
		return UpdateTestFixtures.plan(next.target, operations, finalState);
	}

	private record Fixture(ClientStorage storage, SelectedModpackTarget oldTarget, String oldHash, SelectedModpackTarget target, UpdatePlan plan) {}

	/** The fixture the rc.4/rc.5 journals were written against: mods/old.jar installed, then a generation replacing it with one editable config planned against CRLF live bytes. */
	private Fixture twoGenerations() throws Exception {
		ClientStorage storage = UpdateTestFixtures.storage(temporaryDirectory);
		String old = OverlayTombstoneCommitTest.store(storage, OLD_JAR);
		SelectedModpackTarget oldTarget = UpdateTestFixtures.target(storage, "mods/old.jar", "mod", false, old, OLD_JAR.length);
		assertInstanceOf(UpdateCommit.Applied.class, UpdateTestFixtures.commit(storage, UpdateTestFixtures.plan(oldTarget,
				List.of(new Operation(Root.PROJECTION, "mods/old.jar", OperationType.INSTALL_OBJECT, old, OLD_JAR.length, null)),
				List.of(new ProjectedFile(Root.PROJECTION, "mods/old.jar", true, old, OLD_JAR.length))), oldTarget));
		String lf = OverlayTombstoneCommitTest.store(storage, LF);
		var manifest = GroupManifestValidator.validate(UpdateTestFixtures.fields(CONFIG, "config", true, lf, LF.length));
		PackDocument document = PackDocument.create(manifest, TestPacks.policySha1(manifest), TestPacks.CREATED, oldTarget.document().ownershipLedger());
		TestPacks.stageGeneration(storage, document);
		SelectedModpackTarget target = SelectedModpackTarget.prepare(document, new SelectionIntent(Set.of("main")), new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
		String baseline = HashUtils.sha1(CRLF);
		UpdatePlan plan = UpdateTestFixtures.plan(target, List.of(new Operation(Root.PROJECTION, CONFIG, OperationType.INSTALL_OBJECT, lf, LF.length, null),
				new Operation(Root.PROJECTION, "mods/old.jar", OperationType.DELETE, null, -1, old), new Operation(Root.GAME_DIR, CONFIG, OperationType.INSTALL_OBJECT, lf, LF.length, baseline))
				.stream().sorted(Operation.ORDER).toList(),
				List.of(new ProjectedFile(Root.PROJECTION, CONFIG, true, lf, LF.length), new ProjectedFile(Root.PROJECTION, "mods/old.jar", false, null, -1),
						new ProjectedFile(Root.GAME_DIR, CONFIG, true, lf, LF.length)).stream()
						.sorted(Comparator.comparing((ProjectedFile row) -> row.root().ordinal()).thenComparing(ProjectedFile::relativePath)).toList());
		write(storage.gamePath(CONFIG), CRLF);
		return new Fixture(storage, oldTarget, old, target, plan);
	}

	private static void write(Path path, byte[] bytes) throws Exception {
		Files.createDirectories(path.getParent());
		Files.write(path, bytes);
	}
}
