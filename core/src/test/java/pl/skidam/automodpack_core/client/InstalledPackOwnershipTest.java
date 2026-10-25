package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.loader.ModFileCache;
import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.modpack.generation.Journal;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdateCommit;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * What a client's own generation must leave out, through the real planner and commit: every byte a pack layer held here
 * stays a pack's, and every byte the player put here stays the player's, whatever installs, updates, switches and
 * removals happened in between.
 */
class InstalledPackOwnershipTest {
	private static final SelectionIntent MAIN = new SelectionIntent(Set.of("main"));
	private static final String SHARED = "config/shared.toml";
	private static final String EDITABLE = "config/editable.toml";
	private static final String OTHER = "config/other.toml";

	@TempDir
	Path temporaryDirectory;

	@Test
	void anInstanceRunningAPackHasNoOwnedFiles() throws Exception {
		Client client = client();
		client.apply(pack(client, "packaa1", Map.of(SHARED, file("a"))));

		assertThrows(IOException.class, () -> owned(client, SHARED, "a"), "generation refuses while a pack runs here");
	}

	@Test
	void aPlayersFileAtAPathNoPackShipsStaysTheirs() throws Exception {
		Client client = client();
		client.write(OTHER, "mine");
		client.apply(pack(client, "packaa1", Map.of(SHARED, file("a"))));
		client.deactivate();

		assertFalse(owned(client, OTHER, "mine"));
		assertTrue(owned(client, SHARED, "a"));
	}

	/** Whatever the update leaves at a path it dropped, bytes the player then puts there are the player's. */
	@Test
	void aPlayersFileAfterAnUpdateDroppedThePathStaysTheirs() throws Exception {
		Client client = client();
		client.write(SHARED, "player");
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(SHARED, file("a"), OTHER, file("g")));
		client.apply(first);
		client.apply(pack(client, first, Map.of(OTHER, file("g"))));
		client.deactivate();
		client.write(SHARED, "player");

		assertFalse(owned(client, SHARED, "player"), "a file the player puts back is the player's, not a pack's");
		assertTrue(owned(client, SHARED, "a"));
	}

	@Test
	void aPlayersOriginalADeactivationGaveBackShips() throws Exception {
		Client client = client();
		client.write(SHARED, "player");
		client.apply(pack(client, "packaa1", Map.of(SHARED, file("a"))));
		client.deactivate();

		assertEquals("player", client.read(SHARED));
		assertFalse(owned(client, SHARED, "player"), "the deactivated pack gave the player's original back");
		assertTrue(owned(client, SHARED, "a"));
	}

	@Test
	void bytesAnEarlierPackLeftBehindStayOwnedAfterTwoSwitches() throws Exception {
		Client client = client();
		client.apply(pack(client, "packaa1", Map.of(SHARED, file("a"))));
		client.apply(pack(client, "packbb1", Map.of(SHARED, file("b"))));
		client.apply(pack(client, "packcc1", Map.of(OTHER, file("g"))));
		client.deactivate();

		String left = client.read(SHARED);
		if (left != null) assertTrue(owned(client, SHARED, left), "whatever a switch leaves at a pack's path is a pack's");
		assertTrue(owned(client, SHARED, "a"));
		assertTrue(owned(client, SHARED, "b"));
	}

	@Test
	void thePlayersOwnCopyOfADeactivatedPacksEditableFileShips() throws Exception {
		Client client = client();
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(EDITABLE, editable("a")));
		client.apply(first);
		client.apply(pack(client, first, Map.of(EDITABLE, editable("a"), OTHER, file("g"))));
		client.deactivate();
		client.write(EDITABLE, "the player's own");

		assertFalse(owned(client, EDITABLE, "the player's own"));
		assertTrue(owned(client, EDITABLE, "a"), "the pack's exact bytes are never the player's");
	}

	@Test
	void afterItsPackIsRemovedOtherBytesAtAPathShipButThePacksOwnBytesStayOut() throws Exception {
		Client client = client();
		client.apply(pack(client, "packaa1", Map.of(EDITABLE, editable("a"))));
		client.remove();
		client.write(EDITABLE, "the player's own");

		assertFalse(owned(client, EDITABLE, "the player's own"));
		assertTrue(owned(client, EDITABLE, "a"), "a removed pack's exact bytes are still never the player's");
	}

	@Test
	void aPlayersCopyTheFirstInstallReplacedShipsOnceThePackIsDeactivated() throws Exception {
		Client client = client();
		client.write(EDITABLE, "player");
		client.apply(pack(client, "packaa1", Map.of(EDITABLE, editable("a"))));
		client.deactivate();

		assertEquals("player", client.read(EDITABLE));
		assertFalse(owned(client, EDITABLE, "player"));
	}

	@Test
	void aFileThePlayerMadeBetweenAppliesStaysTheirs() throws Exception {
		Client client = client();
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(SHARED, file("a")));
		client.apply(first);
		client.write(OTHER, "made-later");
		client.apply(pack(client, first, Map.of(SHARED, file("a2"))));
		client.deactivate();

		assertFalse(owned(client, OTHER, "made-later"));
		assertTrue(owned(client, SHARED, "a"));
		assertTrue(owned(client, SHARED, "a2"));
	}

	@Test
	void aRemovedPacksBytesStayOwned() throws Exception {
		Client client = client();
		client.apply(pack(client, "packaa1", Map.of(SHARED, file("a"))));
		client.remove();

		assertTrue(owned(client, SHARED, "a"));
	}

	private static boolean owned(Client client, String path, String content) throws Exception {
		return new ClientGenerationStore(client.storage).packOwnedFiles().owns(path, HashUtils.sha1(content.getBytes(StandardCharsets.UTF_8)));
	}

	private record Spec(String content, boolean editable) {}

	private static Spec file(String content) {
		return new Spec(content, false);
	}

	private static Spec editable(String content) {
		return new Spec(content, true);
	}

	private record Client(ClientStorage storage, ClientUpdatePlanBuilder builder) {
		void apply(SelectedModpackTarget target) throws Exception {
			String id = target.manifest().modpackId();
			new ClientSelectionStore(storage.selectionFile()).put(id, target.selection().intent());
			UpdateTransaction transaction;
			try (FileCache cache = FileCache.open(storage.fileCacheDirectory()); ModFileCache mods = ModFileCache.open(storage.modCacheDirectory())) {
				ClientProjectionView view = ClientProjectionView.observe(storage);
				builder.reconcileEditableState(cache, view, target.flatTarget());
				ClientUpdatePlanBuilder.PreparedPlan prepared = builder.buildPlan(new ClientUpdatePlanBuilder.Input(target, null, new ClientConfigJsons.ClientConfigFieldsV3()), view, cache, mods);
				builder.preparePlanObjects(prepared.plan(), target.flatTarget(), ClientProjectionView.observe(storage), cache);
				transaction = UpdateTransaction.create(prepared.plan(), target, prepared.overlayDigest(), prepared.plannedAgainst());
			}
			UpdateCommit.Outcome outcome = new UpdateCommit(storage).run(new UpdateCommit.Fresh(() -> new UpdateCommit.Built(transaction, target), null));
			assertInstanceOf(UpdateCommit.Applied.class, outcome, String.valueOf(outcome));
		}

		void remove() throws Exception {
			removeOrDeactivate(true);
		}

		void deactivate() throws Exception {
			removeOrDeactivate(false);
		}

		private void removeOrDeactivate(boolean remove) throws Exception {
			ClientUpdatePlanBuilder.RemovalPreparation prepared = builder.prepareRemoval();
			String id = prepared.installed().modpackId;
			if (remove) new ClientSelectionStore(storage.selectionFile()).remove(id);
			UpdateTransaction transaction = remove
					? UpdateTransaction.createRemoval(prepared.plan(), ClientPlatform.current(), prepared.expectedPriorIntent(), storage.overlayDigest(id), prepared.plannedAgainst())
					: UpdateTransaction.createDeactivation(prepared.plan(), ClientPlatform.current(), prepared.expectedPriorIntent(), storage.overlayDigest(id), prepared.plannedAgainst());
			UpdateCommit.Outcome outcome = new UpdateCommit(storage).run(new UpdateCommit.Fresh(() -> UpdateCommit.Built.of(transaction), null));
			assertInstanceOf(UpdateCommit.Applied.class, outcome, String.valueOf(outcome));
		}

		void write(String path, String content) throws Exception {
			Path file = storage.gamePath(path);
			Files.createDirectories(file.getParent());
			Files.writeString(file, content, StandardCharsets.UTF_8);
		}

		String read(String path) throws Exception {
			Path file = storage.gamePath(path);
			return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
		}
	}

	private Client client() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		return new Client(storage, new ClientUpdatePlanBuilder(storage, new ModpackLoaderService() {
			@Override
			public void loadModpack(ModpackLoadRequest request) {}
		}, "fabric", (uid, version) -> true));
	}

	private static SelectedModpackTarget pack(Client client, String modpackId, Map<String, Spec> files) throws Exception {
		return generation(client.storage, modpackId, null, files, 0);
	}

	private static SelectedModpackTarget pack(Client client, SelectedModpackTarget parent, Map<String, Spec> files) throws Exception {
		return generation(client.storage, parent.manifest().modpackId(), parent, files, 1);
	}

	private static SelectedModpackTarget generation(ClientStorage storage, String modpackId, SelectedModpackTarget parent, Map<String, Spec> specs, int version) throws Exception {
		Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields> files = new LinkedHashMap<>();
		for (var entry : specs.entrySet()) {
			byte[] content = entry.getValue().content().getBytes(StandardCharsets.UTF_8);
			ClientObjectStore.storeObject(storage, HashUtils.sha1(content), content);
			files.put(entry.getKey(), new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(content.length), entry.getValue().editable(),
					HashUtils.sha1(content), "0"));
		}
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = modpackId;
		fields.modpackName = modpackId;
		fields.loader = "";
		fields.loaderVersion = "";
		fields.mcVersion = "";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = true;
		group.defaultSelected = true;
		group.files = files;
		fields.categories = Map.of("General", Map.of("main", group));
		var manifest = GroupManifestValidator.validate(fields);
		PackDocument document = parent == null ? TestPacks.document(manifest) : TestPacks.document(manifest, parent.document().ownershipLedger(), TestPacks.CREATED.plusSeconds(version));
		TestPacks.stageGeneration(storage, document);
		if (parent != null && parent.document().contentToken().equals(document.contentToken()) && !parent.document().policySha1().equals(document.policySha1())) {
			// A policy-only publish: the server appends a journal entry under the same content token (the shared fixture skips it).
			Journal journal = Journal.open(storage.historyJournalFile(modpackId));
			journal.append(new JournalEntry(journal.head().seq() + 1, document.contentToken(), document.policySha1(), document.createdAt(), "", JournalEntry.NO_RESTORE, List.of()));
		}
		return SelectedModpackTarget.prepare(document, parent == null ? null : MAIN, MAIN, ClientPlatform.current());
	}
}
