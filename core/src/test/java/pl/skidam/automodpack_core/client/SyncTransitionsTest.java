package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
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
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdateCommit;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * Cross-pack and dirty-instance transitions, end to end through the real planner and commit: after every action the game
 * directory must hold exactly what the active pack, the player's own files and the player's edits say, never what an
 * earlier pack left behind.
 */
class SyncTransitionsTest {
	private static final SelectionIntent MAIN = new SelectionIntent(Set.of("main"));
	private static final String F = "config/shared.toml";
	private static final String E = "config/editable.toml";
	private static final String G = "config/other.toml";

	@TempDir
	Path temporaryDirectory;

	/** A file pack A shipped, then pack B shipped with other bytes, belongs to neither once the player moves on to C. */
	@Test
	void aFileTwoEarlierPacksShippedIsGoneAfterSwitchingToAThird() throws Exception {
		Client client = client();
		client.apply(pack(client, "packaa1", Map.of(F, file("a"))));
		client.apply(pack(client, "packbb1", Map.of(F, file("b"))));
		assertEquals("b", client.read(F));

		client.apply(pack(client, "packcc1", Map.of(G, file("g"))));

		assertNull(client.read(F), "C ships no " + F + " and the player never had one");
		assertEquals("g", client.read(G));
	}

	/** The same through a removal: after A then B, removing B leaves no pack file of A or B behind. */
	@Test
	void removingTheSecondPackLeavesNoFileOfTheFirst() throws Exception {
		Client client = client();
		client.apply(pack(client, "packaa1", Map.of(F, file("a"))));
		client.apply(pack(client, "packbb1", Map.of(F, file("b"))));

		client.remove();

		assertNull(client.read(F), "neither the player nor an active pack owns " + F);
	}

	/** A player's own file a pack replaced comes back when the pack lets go of the path, even after another pack held it in between. */
	@Test
	void thePlayersOriginalComesBackAfterTwoPacksHeldThePath() throws Exception {
		Client client = client();
		client.write(F, "player");
		client.apply(pack(client, "packaa1", Map.of(F, file("a"))));
		client.apply(pack(client, "packbb1", Map.of(F, file("b"))));

		client.apply(pack(client, "packcc1", Map.of(G, file("g"))));

		assertEquals("player", client.read(F));
	}

	/** The baseline is what the path held before the pack claimed it this time, not before the pack's first install ever. */
	@Test
	void aFileThePlayerMadeAfterRemovingAPackComesBackWhenTheReinstalledPackLetsGo() throws Exception {
		Client client = client();
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(G, file("g")));
		client.apply(first);
		client.remove();
		client.write(F, "player");
		SelectedModpackTarget claiming = pack(client, first, Map.of(G, file("g"), F, file("a")));
		client.apply(claiming);
		assertEquals("a", client.read(F));

		client.remove();

		assertEquals("player", client.read(F));
	}

	@Test
	void thePlayersOriginalComesBackWhenTheOnlyPackLetsGo() throws Exception {
		Client client = client();
		client.write(F, "player");
		client.apply(pack(client, "packaa1", Map.of(F, file("a"))));

		client.apply(pack(client, "packbb1", Map.of(G, file("g"))));

		assertEquals("player", client.read(F));
	}

	/** A pack's own earlier bytes are never mistaken for the player's: A then a newer generation of A then B, the path ends empty. */
	@Test
	void anUpdatedPackDoesNotRestoreItsOwnOlderBytes() throws Exception {
		Client client = client();
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(F, file("a1")));
		client.apply(first);
		client.apply(pack(client, first, Map.of(F, file("a2"))));

		client.apply(pack(client, "packbb1", Map.of(G, file("g"))));

		assertNull(client.read(F));
	}

	@Test
	void anEditedEditableFileLeavesWithItsPackAndComesBackWithIt() throws Exception {
		Client client = client();
		SelectedModpackTarget a = pack(client, "packaa1", Map.of(E, editable("a")));
		client.apply(a);
		client.write(E, "edited");

		client.apply(pack(client, "packbb1", Map.of(G, file("g"))));
		assertNull(client.read(E), "B does not ship " + E);

		client.apply(a);
		assertEquals("edited", client.read(E));
	}

	@Test
	void anEditedEditableFileGivesWayToTheNextPacksImmutableVersionAndComesBack() throws Exception {
		Client client = client();
		SelectedModpackTarget a = pack(client, "packaa1", Map.of(E, editable("a")));
		client.apply(a);
		client.write(E, "edited");

		client.apply(pack(client, "packbb1", Map.of(E, file("b"))));
		assertEquals("b", client.read(E));

		client.apply(a);
		assertEquals("edited", client.read(E));
	}

	/** An editable file of A that the player edited, while B makes the same path editable too: each pack keeps its own edits. */
	@Test
	void editsOfTheSamePathStayWithTheirPack() throws Exception {
		Client client = client();
		SelectedModpackTarget a = pack(client, "packaa1", Map.of(E, editable("a")));
		SelectedModpackTarget b = pack(client, "packbb1", Map.of(E, editable("b")));
		client.apply(a);
		client.write(E, "edited-in-a");
		client.apply(b);
		assertEquals("b", client.read(E));
		client.write(E, "edited-in-b");

		client.apply(a);
		assertEquals("edited-in-a", client.read(E));
		client.apply(b);
		assertEquals("edited-in-b", client.read(E));
	}

	/** Removing the pack keeps a file the player changed. */
	/** Edits to the pack's copy of an editable file leave with the pack; with nothing of the player's before it, nothing is left. */
	@Test
	void removalDiscardsEditsToThePacksEditableCopy() throws Exception {
		Client client = client();
		client.apply(pack(client, "packaa1", Map.of(E, editable("a"), F, file("a"))));
		client.write(E, "edited");

		client.remove();

		assertNull(client.read(E));
		assertNull(client.read(F));
	}

	/** On first install over a player's file the pack wins, and removal gives the player's file back over any edits to the pack's copy. */
	@Test
	void removalRestoresThePlayersOriginalEvenAfterTheyEditedThePacksEditableCopy() throws Exception {
		Client client = client();
		client.write(E, "player");
		client.apply(pack(client, "packaa1", Map.of(E, editable("a"))));
		client.write(E, "edited");

		client.remove();

		assertEquals("player", client.read(E));
	}

	@Test
	void thePlayersOriginalSurvivesRemoveReinstallUpdateRemove() throws Exception {
		Client client = client();
		client.write(F, "player");
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(F, file("a")));
		client.apply(first);
		client.remove();
		assertEquals("player", client.read(F));
		SelectedModpackTarget again = pack(client, "packaa1", Map.of(F, file("a")));
		client.apply(again);
		client.apply(pack(client, again, Map.of(F, file("a2"))));
		client.apply(pack(client, again, Map.of(F, file("a3"), G, file("g"))));
		client.remove();
		assertEquals("player", client.read(F));
	}

	@Test
	void thePlayersOriginalSurvivesSwitchingThroughAPackThatDropsItAndBack() throws Exception {
		Client client = client();
		client.write(F, "player");
		SelectedModpackTarget a = pack(client, "packaa1", Map.of(F, file("a")));
		client.apply(a);
		client.apply(pack(client, "packbb1", Map.of(G, file("g"))));
		assertEquals("player", client.read(F));
		client.write(F, "player2");
		client.apply(a);
		client.remove();
		assertEquals("player2", client.read(F), "the newest bytes the player had before A claimed the path again");
	}

	/** A file the player deleted from an editable path stays away on a same-pack update that does not change it. */
	@Test
	void aDeletedEditableFileStaysDeletedAcrossAnUnrelatedUpdate() throws Exception {
		Client client = client();
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(E, editable("a"), F, file("f1")));
		client.apply(first);
		Files.delete(client.storage.gamePath(E));

		client.apply(pack(client, first, Map.of(E, editable("a"), F, file("f2"))));

		assertNull(client.read(E));
		assertEquals("f2", client.read(F));
	}

	/** A pack-owned non-editable file the player changed is brought back on the next same-pack sync. */
	@Test
	void aDriftedPackFileIsBroughtBackInSync() throws Exception {
		Client client = client();
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(F, file("a")));
		client.apply(first);
		client.write(F, "drift");

		client.apply(pack(client, first, Map.of(F, file("a"), G, file("g"))));

		assertEquals("a", client.read(F));
	}

	/** An unmanaged file of the player is never touched by install, switch or removal. */
	@Test
	void anUnrelatedPlayerFileSurvivesEverything() throws Exception {
		Client client = client();
		client.write("config/mine.toml", "mine");
		client.apply(pack(client, "packaa1", Map.of(F, file("a"))));
		client.apply(pack(client, "packbb1", Map.of(G, file("g"))));
		client.remove();

		assertEquals("mine", client.read("config/mine.toml"));
	}

	@Test
	void thePlayersOriginalComesBackWhenThePackIsRemoved() throws Exception {
		Client client = client();
		client.write(F, "player");
		client.apply(pack(client, "packaa1", Map.of(F, file("a"))));

		client.remove();

		assertEquals("player", client.read(F));
	}

	/** A and B ship the very same bytes at a path: once C takes over, the path is still neither pack's to keep. */
	@Test
	void identicalBytesTwoPacksShippedAreGoneAfterSwitchingToAThird() throws Exception {
		Client client = client();
		client.apply(pack(client, "packaa1", Map.of(F, file("same"))));
		client.apply(pack(client, "packbb1", Map.of(F, file("same"))));

		client.apply(pack(client, "packcc1", Map.of(G, file("g"))));

		assertNull(client.read(F));
	}

	/** The server taking editability away is a server change: its version wins over the player's edit. */
	@Test
	void anEditableFileTurnedImmutableGetsThePackVersion() throws Exception {
		Client client = client();
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(E, editable("a")));
		client.apply(first);
		client.write(E, "edited");

		client.apply(pack(client, first, Map.of(E, file("a"))));

		assertEquals("a", client.read(E));
	}

	/** An immutable file turned editable keeps its bytes and starts collecting edits. */
	@Test
	void anImmutableFileTurnedEditableKeepsLaterEdits() throws Exception {
		Client client = client();
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(E, file("a")));
		client.apply(first);
		SelectedModpackTarget second = pack(client, first, Map.of(E, editable("a")));
		client.apply(second);
		client.write(E, "edited");

		client.apply(generation(client.storage, "packaa1", second, Map.of(E, editable("a"), G, file("g")), 2));

		assertEquals("edited", client.read(E));
	}

	/** The player's removal of an editable file belongs to that pack: B brings its own version, back on A it is gone again. */
	@Test
	void aRemovalOfAnEditableFileStaysWithItsPack() throws Exception {
		Client client = client();
		SelectedModpackTarget a = pack(client, "packaa1", Map.of(E, editable("a")));
		client.apply(a);
		Files.delete(client.storage.gamePath(E));

		client.apply(pack(client, "packbb1", Map.of(E, editable("b"))));
		assertEquals("b", client.read(E));

		client.apply(a);
		assertNull(client.read(E));
	}

	/** The principle: an editable file the server replaces gets the new version once, and edits made after that are kept. */
	@Test
	void editsAfterAServerReplacementAreKept() throws Exception {
		Client client = client();
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(E, editable("a1")));
		client.apply(first);
		client.write(E, "edit-1");
		SelectedModpackTarget second = pack(client, first, Map.of(E, editable("a2")));
		client.apply(second);
		assertEquals("a2", client.read(E));
		client.write(E, "edit-2");

		client.apply(generation(client.storage, "packaa1", second, Map.of(E, editable("a2"), G, file("g")), 2));

		assertEquals("edit-2", client.read(E));
	}

	/** An update that drops an edited editable file touched it: the file leaves with the pack's version of the path. */
	@Test
	void anUpdateDroppingAnEditedEditableFileDeletesIt() throws Exception {
		Client client = client();
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(E, editable("a"), F, file("f")));
		client.apply(first);
		client.write(E, "edited");

		client.apply(pack(client, first, Map.of(F, file("f"))));

		assertNull(client.read(E));
		assertEquals("f", client.read(F));
	}

	/** A file the pack stops shipping is dropped while the player stays on the pack, edited or not, and removing the pack later has nothing of it to give back. */
	@Test
	void anUpdateDroppingAPathDoesNotGiveThePlayersFileBackNowOrOnRemoval() throws Exception {
		Client client = client();
		client.write(E, "player e");
		client.write(F, "player f");
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(E, editable("a"), F, file("f"), G, file("g")));
		client.apply(first);
		client.write(E, "edited");

		client.apply(pack(client, first, Map.of(G, file("g"))));
		assertNull(client.read(E));
		assertNull(client.read(F));

		client.remove();
		assertNull(client.read(E));
		assertNull(client.read(F));
	}

	/** Once the pack dropped a path, a later generation shipping it again owns it from scratch, so the removal clears it. */
	@Test
	void aPathThePackDroppedAndShipsAgainLeavesNothingOnRemoval() throws Exception {
		Client client = client();
		client.write(E, "player");
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(E, editable("a"), G, file("g")));
		client.apply(first);
		SelectedModpackTarget second = pack(client, first, Map.of(G, file("g")));
		client.apply(second);
		client.apply(generation(client.storage, "packaa1", second, Map.of(E, editable("a3"), G, file("g")), 2));
		assertEquals("a3", client.read(E));

		client.remove();

		assertNull(client.read(E));
	}

	@Test
	void aMovedPackFileLeavesItsOldPath() throws Exception {
		Client client = client();
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(F, file("f")));
		client.apply(first);

		client.apply(pack(client, first, Map.of("config/sub/shared.toml", file("f"))));

		assertNull(client.read(F));
		assertEquals("f", client.read("config/sub/shared.toml"));
	}

	@Test
	void switchingAwayFromAnEditedEditableFileGivesThePlayersOwnFileBack() throws Exception {
		Client client = client();
		client.write(E, "player");
		client.apply(pack(client, "packaa1", Map.of(E, editable("a"))));
		client.write(E, "edited");

		client.apply(pack(client, "packbb1", Map.of(G, file("g"))));

		assertEquals("player", client.read(E));
	}

	/** The pack wins a path the player already had, on first install, and the player's copy comes back with the removal. */
	@Test
	void firstInstallOverAPlayersEditableFileTakesThePackVersionAndRemovalRestoresThePlayers() throws Exception {
		Client client = client();
		client.write(E, "player");
		client.apply(pack(client, "packaa1", Map.of(E, editable("a"))));
		assertEquals("a", client.read(E));

		client.remove();

		assertEquals("player", client.read(E));
	}

	@Test
	void thePlayersEditableFileComesBackAfterSwitchingToAnotherEditablePackAndRemovingIt() throws Exception {
		Client client = client();
		client.write(E, "player");
		client.apply(pack(client, "packaa1", Map.of(E, editable("a"))));
		client.apply(pack(client, "packbb1", Map.of(E, editable("b"))));
		assertEquals("b", client.read(E));

		client.remove();

		assertEquals("player", client.read(E));
	}

	@Test
	void thePlayersEditableFileComesBackAfterSwitchingAwayAndBackAndRemoving() throws Exception {
		Client client = client();
		client.write(E, "player");
		SelectedModpackTarget a = pack(client, "packaa1", Map.of(E, editable("a")));
		client.apply(a);
		client.apply(pack(client, "packbb1", Map.of(E, editable("b"))));
		client.apply(a);
		assertEquals("a", client.read(E));

		client.remove();

		assertEquals("player", client.read(E));
	}

	@Test
	void thePlayersEditableFileComesBackWhenSwitchingToAPackWithoutItAndStaysThroughItsRemoval() throws Exception {
		Client client = client();
		client.write(E, "player");
		client.apply(pack(client, "packaa1", Map.of(E, editable("a"))));

		client.apply(pack(client, "packbb1", Map.of(G, file("g"))));
		assertEquals("player", client.read(E));

		client.remove();

		assertEquals("player", client.read(E));
	}

	@Test
	void thePlayersEditableFileComesBackAfterSeveralUpdatesOfThePack() throws Exception {
		Client client = client();
		client.write(E, "player");
		SelectedModpackTarget first = pack(client, "packaa1", Map.of(E, editable("a1")));
		client.apply(first);
		SelectedModpackTarget second = pack(client, first, Map.of(E, editable("a2")));
		client.apply(second);
		assertEquals("a2", client.read(E));
		client.apply(generation(client.storage, "packaa1", second, Map.of(E, editable("a3"), G, file("g")), 2));
		assertEquals("a3", client.read(E));

		client.remove();

		assertEquals("player", client.read(E));
	}

	/** Edits made to the pack's copy leave with the pack, so removing it after switches gives back the player's file from before. */
	@Test
	void removalAfterSwitchesRestoresThePlayersFileOverEditsMadeInThePack() throws Exception {
		Client client = client();
		client.write(E, "player");
		SelectedModpackTarget a = pack(client, "packaa1", Map.of(E, editable("a")));
		client.apply(a);
		client.write(E, "edited");
		client.apply(pack(client, "packbb1", Map.of(E, editable("b"))));
		client.apply(a);
		assertEquals("edited", client.read(E));

		client.remove();

		assertEquals("player", client.read(E));
	}

	/**
	 * A Linux-only pack may ship two names that differ only in case; with equal bytes both projection rows are hard links
	 * to one store object, so the filesystem calls them the same file although they are two directory entries.
	 */
	@Test
	void twoCaseDistinctPackFilesWithEqualBytesStayTwoFilesAcrossAnUpdate() throws Exception {
		Assumptions.assumeTrue(ClientPlatform.LINUX.equals(ClientPlatform.current()));
		Client client = client();
		Files.createDirectories(client.storage.gamePath("config"));
		Assumptions.assumeFalse(Files.exists(client.storage.gamePath("CONFIG")), "needs a case-sensitive filesystem");
		SelectedModpackTarget first = generation(client.storage, "packaa1", null, Map.of("config/Dup.toml", file("x"), "config/dup.toml", file("x")), 0, true);
		client.apply(first);
		assertEquals("x", client.read("config/Dup.toml"));
		assertEquals("x", client.read("config/dup.toml"));

		client.apply(generation(client.storage, "packaa1", first, Map.of("config/Dup.toml", file("x"), "config/dup.toml", file("x"), G, file("g")), 1, true));

		assertEquals("x", client.read("config/Dup.toml"));
		assertEquals("x", client.read("config/dup.toml"));
		assertEquals("g", client.read(G));
	}

	@Test
	void aDirectoryAtAPackPathRefusesThePlanAndChangesNothing() throws Exception {
		Client client = client();
		Files.createDirectories(client.storage.gamePath(F).resolve("inside"));

		IOException refused = assertThrows(IOException.class, () -> client.apply(pack(client, "packaa1", Map.of(F, file("a"), G, file("g")))));

		assertTrue(refused.getMessage().startsWith("Pack path is taken by a directory") && refused.getMessage().endsWith("shared.toml"), refused.getMessage());
		assertTrue(Files.isDirectory(client.storage.gamePath(F).resolve("inside")));
		assertNull(client.read(G));
		assertNull(client.storage.readActiveState());
	}

	@Test
	void aSymbolicLinkAtAPackPathRefusesThePlanAndLeavesItsTargetAlone() throws Exception {
		Client client = client();
		Path elsewhere = Files.writeString(temporaryDirectory.resolve("dotfiles.toml"), "mine");
		Files.createDirectories(client.storage.gamePath("config"));
		Files.createSymbolicLink(client.storage.gamePath(F), elsewhere);

		IOException refused = assertThrows(IOException.class, () -> client.apply(pack(client, "packaa1", Map.of(F, file("a"), G, file("g")))));

		assertTrue(refused.getMessage().startsWith("Pack path contains a symbolic link"), refused.getMessage());
		assertTrue(Files.isSymbolicLink(client.storage.gamePath(F)));
		assertEquals("mine", Files.readString(elsewhere));
		assertNull(client.read(G));
	}

	@Test
	void aSymbolicallyLinkedPackDirectoryRefusesThePlanAndLeavesItsTargetAlone() throws Exception {
		Client client = client();
		Path shared = Files.createDirectories(temporaryDirectory.resolve("shared-config"));
		Files.createSymbolicLink(client.storage.gamePath("config"), shared);

		IOException refused = assertThrows(IOException.class, () -> client.apply(pack(client, "packaa1", Map.of(F, file("a")))));

		assertTrue(refused.getMessage().startsWith("Pack path contains a symbolic link"), refused.getMessage());
		try (var left = Files.list(shared)) {
			assertEquals(0, left.count());
		}
		assertNull(client.storage.readActiveState());
	}

	/** An instance moved to another drive and reached through a link syncs like any other. */
	@Test
	void aSymbolicallyLinkedGameDirectorySyncs() throws Exception {
		Files.createSymbolicLink(temporaryDirectory.resolve("game"), Files.createDirectories(temporaryDirectory.resolve("real-game")));
		Client client = client();

		client.apply(pack(client, "packaa1", Map.of(F, file("a"))));

		assertEquals("a", client.read(F));
	}

	/** Deselecting the group that holds a path is leaving it: the player's own file comes back. */
	@Test
	void deselectingTheGroupGivesThePlayersFileBack() throws Exception {
		Client client = client();
		client.write(F, "player");
		SelectedModpackTarget both = grouped(client.storage, "packaa1", null, Map.of("main", group(true, Set.of(), Map.of(G, file("g"))), "extra", group(false, Set.of(), Map.of(F, file("a")))), 0,
				Set.of("main", "extra"));
		client.apply(both);
		assertEquals("a", client.read(F));

		client.apply(grouped(client.storage, "packaa1", both, Map.of("main", group(true, Set.of(), Map.of(G, file("g"))), "extra", group(false, Set.of(), Map.of(F, file("a")))), 1,
				Set.of("main")));

		assertEquals("player", client.read(F));
	}

	/** The server respelling the path in the very update that deselects its group is still leaving it: the player's file comes back. */
	@Test
	void deselectingTheGroupInTheUpdateThatRespellsThePathGivesThePlayersFileBack() throws Exception {
		Client client = client();
		String lower = "config/respell.toml";
		String upper = "config/Respell.toml";
		client.write(lower, "player");
		SelectedModpackTarget both = grouped(client.storage, "packaa1", null, Map.of("main", group(true, Set.of(), Map.of(G, file("g"))), "extra", group(false, Set.of(), Map.of(lower, file("a")))), 0,
				Set.of("main", "extra"));
		client.apply(both);
		assertEquals("a", client.read(lower));

		client.apply(grouped(client.storage, "packaa1", both, Map.of("main", group(true, Set.of(), Map.of(G, file("g"))), "extra", group(false, Set.of(), Map.of(upper, file("a")))), 1,
				Set.of("main")));

		assertEquals("player", client.read(lower), "leaving the group must give the original back whatever the server now calls the path");
	}

	/** Deselecting the group also gives the player's file back over their edits to the pack's editable copy. */
	@Test
	void deselectingTheGroupGivesThePlayersFileBackOverEdits() throws Exception {
		Client client = client();
		client.write(E, "player");
		SelectedModpackTarget both = grouped(client.storage, "packaa1", null, Map.of("main", group(true, Set.of(), Map.of(G, file("g"))), "extra", group(false, Set.of(), Map.of(E, editable("a")))), 0,
				Set.of("main", "extra"));
		client.apply(both);
		client.write(E, "edited");

		client.apply(grouped(client.storage, "packaa1", both, Map.of("main", group(true, Set.of(), Map.of(G, file("g"))), "extra", group(false, Set.of(), Map.of(E, editable("a")))), 1,
				Set.of("main")));

		assertEquals("player", client.read(E));
	}

	/** The server moving a path into a group the player never picked is the pack dropping it: deleted, and never given back later. */
	@Test
	void aPathTheServerMovesIntoAnUnpickedGroupIsDeletedAndStaysGoneOnRemoval() throws Exception {
		Client client = client();
		client.write(F, "player");
		SelectedModpackTarget first = grouped(client.storage, "packaa1", null, Map.of("main", group(true, Set.of(), Map.of(G, file("g"), F, file("a")))), 0, Set.of("main"));
		client.apply(first);
		assertEquals("a", client.read(F));

		client.apply(grouped(client.storage, "packaa1", first, Map.of("main", group(true, Set.of(), Map.of(G, file("g"))), "extra", group(false, Set.of(), Map.of(F, file("a")))), 1,
				Set.of("main")));
		assertNull(client.read(F));

		client.remove();
		assertNull(client.read(F));
	}

	/** Leaving restores what the player had just before the pack claimed the path this time: edits made while deactivated come back, not the first original. */
	@Test
	void editsMadeWhileDeactivatedComeBackWhenThePackIsDeactivatedAgain() throws Exception {
		Client client = client();
		client.write(F, "player");
		SelectedModpackTarget pack = pack(client, "packaa1", Map.of(F, file("a")));
		client.apply(pack);
		client.deactivate();
		assertEquals("player", client.read(F));
		client.write(F, "edited");
		client.apply(pack);
		assertEquals("a", client.read(F));

		client.deactivate();

		assertEquals("edited", client.read(F));
	}

	/** The same through switches: A, B, back to A, then removal gives back what the player had before A first claimed it. */
	@Test
	void thePlayersOriginalComesBackAfterSwitchingAwayAndBackThenRemoving() throws Exception {
		Client client = client();
		client.write(F, "player");
		SelectedModpackTarget a = pack(client, "packaa1", Map.of(F, file("a")));
		client.apply(a);
		client.apply(pack(client, "packbb1", Map.of(F, file("b"))));
		client.apply(a);

		client.remove();

		assertEquals("player", client.read(F));
	}

	/** Edits made between two pack runs are the original for the later run, also when it starts with a switch. */
	@Test
	void editsMadeWhileUnmanagedComeBackAfterASwitchAndRemoval() throws Exception {
		Client client = client();
		client.write(F, "player");
		client.apply(pack(client, "packaa1", Map.of(F, file("a"))));
		client.deactivate();
		client.write(F, "edited");
		client.apply(pack(client, "packbb1", Map.of(G, file("g"))));
		client.apply(pack(client, "packcc1", Map.of(F, file("c"))));

		client.remove();

		assertEquals("edited", client.read(F));
	}

	/** A file the player deleted while no pack held the path stays deleted when the next pack run ends. */
	@Test
	void anOriginalThePlayerDeletedWhileDeactivatedStaysGone() throws Exception {
		Client client = client();
		client.write(F, "player");
		SelectedModpackTarget pack = pack(client, "packaa1", Map.of(F, file("a")));
		client.apply(pack);
		client.deactivate();
		Files.delete(client.storage.gamePath(F));
		client.apply(pack);

		client.deactivate();

		assertNull(client.read(F));
	}

	/** A file only the pack ever had is gone on deactivation and nothing appears in its place. */
	@Test
	void aFileOnlyThePackHadIsGoneOnDeactivation() throws Exception {
		Client client = client();
		client.apply(pack(client, "packaa1", Map.of(F, file("a"))));

		client.deactivate();

		assertNull(client.read(F));
	}

	/** Deselect, reselect, deselect: each leave gives back what the player had right before that selection claimed the path. */
	@Test
	void reselectingAGroupAndDeselectingAgainGivesBackTheLatestOriginal() throws Exception {
		Client client = client();
		client.write(F, "player");
		Map<String, GroupSpec> groups = Map.of("main", group(true, Set.of(), Map.of(G, file("g"))), "extra", group(false, Set.of(), Map.of(F, file("a"))));
		SelectedModpackTarget both = grouped(client.storage, "packaa1", null, groups, 0, Set.of("main", "extra"));
		client.apply(both);
		SelectedModpackTarget main = grouped(client.storage, "packaa1", both, groups, 1, Set.of("main"));
		client.apply(main);
		client.write(F, "edited");
		SelectedModpackTarget again = grouped(client.storage, "packaa1", main, groups, 2, Set.of("main", "extra"));
		client.apply(again);
		assertEquals("a", client.read(F));

		client.apply(grouped(client.storage, "packaa1", again, groups, 3, Set.of("main")));

		assertEquals("edited", client.read(F));
	}

	/** An original whose bytes the store no longer has cannot be given back, so the pack's copy stays rather than leaving nothing. */
	@Test
	void anOriginalMissingFromTheStoreLeavesThePacksCopy() throws Exception {
		Client client = client();
		client.write(F, "player");
		client.apply(pack(client, "packaa1", Map.of(F, file("a"))));
		Files.delete(client.storage.objectFile(HashUtils.sha1("player".getBytes(StandardCharsets.UTF_8))));

		client.remove();

		assertEquals("a", client.read(F));
	}

	private record GroupSpec(boolean required, Set<String> platforms, Map<String, Spec> files) {}

	private static GroupSpec group(boolean required, Set<String> platforms, Map<String, Spec> files) {
		return new GroupSpec(required, platforms, files);
	}

	private static SelectedModpackTarget grouped(ClientStorage storage, String modpackId, SelectedModpackTarget parent, Map<String, GroupSpec> groups, int version, Set<String> requested)
			throws Exception {
		Map<String, ModpackJsons.CompleteModpackContentFields.ModpackGroupFields> groupFields = new LinkedHashMap<>();
		for (var groupEntry : groups.entrySet()) {
			Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields> files = new LinkedHashMap<>();
			for (var entry : groupEntry.getValue().files().entrySet()) {
				byte[] content = entry.getValue().content().getBytes(StandardCharsets.UTF_8);
				ClientObjectStore.storeObject(storage, HashUtils.sha1(content), content);
				files.put(entry.getKey(), new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(content.length), entry.getValue().editable(), HashUtils.sha1(content), "0"));
			}
			ModpackJsons.CompleteModpackContentFields.ModpackGroupFields fields = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
			fields.required = groupEntry.getValue().required();
			fields.defaultSelected = groupEntry.getValue().required();
			fields.compatiblePlatforms = groupEntry.getValue().platforms();
			fields.files = files;
			groupFields.put(groupEntry.getKey(), fields);
		}
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = modpackId;
		fields.modpackName = modpackId;
		fields.loader = "";
		fields.loaderVersion = "";
		fields.mcVersion = "";
		fields.categories = Map.of("General", groupFields);
		var manifest = GroupManifestValidator.validate(fields);
		PackDocument document = parent == null ? TestPacks.document(manifest) : TestPacks.document(manifest, parent.document().ownershipLedger(), TestPacks.CREATED.plusSeconds(version));
		TestPacks.stageGeneration(storage, document);
		SelectionIntent intent = new SelectionIntent(requested);
		return SelectedModpackTarget.prepare(document, parent == null ? null : parent.selection().intent(), intent, ClientPlatform.current());
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
			ClientUpdatePlanBuilder.RemovalPreparation prepared = builder.prepareRemoval();
			String id = prepared.installed().modpackId;
			new ClientSelectionStore(storage.selectionFile()).remove(id);
			UpdateTransaction transaction = UpdateTransaction.createRemoval(prepared.plan(), ClientPlatform.current(), prepared.expectedPriorIntent(), storage.overlayDigest(id),
					prepared.plannedAgainst());
			UpdateCommit.Outcome outcome = new UpdateCommit(storage).run(new UpdateCommit.Fresh(() -> UpdateCommit.Built.of(transaction), null));
			assertInstanceOf(UpdateCommit.Applied.class, outcome, String.valueOf(outcome));
		}

		void deactivate() throws Exception {
			ClientUpdatePlanBuilder.RemovalPreparation prepared = builder.prepareRemoval();
			UpdateTransaction transaction = UpdateTransaction.createDeactivation(prepared.plan(), ClientPlatform.current(), prepared.expectedPriorIntent(),
					storage.overlayDigest(prepared.installed().modpackId), prepared.plannedAgainst());
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
		return generation(storage, modpackId, parent, specs, version, false);
	}

	private static SelectedModpackTarget generation(ClientStorage storage, String modpackId, SelectedModpackTarget parent, Map<String, Spec> specs, int version, boolean linuxOnly) throws Exception {
		Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields> files = new LinkedHashMap<>();
		for (var entry : specs.entrySet()) {
			byte[] content = entry.getValue().content().getBytes(StandardCharsets.UTF_8);
			ClientObjectStore.storeObject(storage, HashUtils.sha1(content), content);
			files.put(entry.getKey(), new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(content.length), entry.getValue().editable(), HashUtils.sha1(content), "0"));
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
		if (linuxOnly) {
			ModpackJsons.CompleteModpackContentFields.ModpackGroupFields linux = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
			linux.required = false;
			linux.defaultSelected = true;
			linux.compatiblePlatforms = Set.of(ClientPlatform.LINUX.id());
			linux.files = files;
			group.files = Map.of();
			fields.categories = Map.of("General", Map.of("main", group, "linux", linux));
		} else fields.categories = Map.of("General", Map.of("main", group));
		var manifest = GroupManifestValidator.validate(fields);
		PackDocument document = parent == null ? TestPacks.document(manifest) : TestPacks.document(manifest, parent.document().ownershipLedger(), TestPacks.CREATED.plusSeconds(version));
		TestPacks.stageGeneration(storage, document);
		if (parent != null && parent.document().contentToken().equals(document.contentToken()) && !parent.document().policySha1().equals(document.policySha1())) {
			// A policy-only publish: the server appends a journal entry under the same content token (the shared fixture skips it).
			Journal journal = Journal.open(storage.historyJournalFile(modpackId));
			journal.append(new JournalEntry(journal.head().seq() + 1, document.contentToken(), document.policySha1(), document.createdAt(), "", JournalEntry.NO_RESTORE, List.of()));
		}
		SelectionIntent intent = linuxOnly ? new SelectionIntent(Set.of("main", "linux")) : MAIN;
		return SelectedModpackTarget.prepare(document, parent == null ? null : intent, intent, ClientPlatform.current());
	}
}
