package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.StoragePaths;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.utils.HashUtils;

class GeneratedCopyStateCorruptionTest {
	private static final String MODPACK_ID = "abc1234";
	private static final String CONTENT_TOKEN = HashUtils.sha1("generation");
	private static final String SELECTION_DIGEST = HashUtils.sha1("selection");

	@TempDir
	Path tempDir;

	@Test
	void stateWrittenInAForgottenFormatIsSetAsideAndReadsEmpty() throws Exception {
		ClientStorage storage = TestDataRoot.open(tempDir.resolve("game"), tempDir.resolve("data"));
		Path path = storage.generatedCopiesFile();
		Files.writeString(path, "{\"schemaVersion\":2,\"states\":[]}", StandardCharsets.UTF_8);

		GeneratedCopyState state = GeneratedCopyState.read(storage, MODPACK_ID, CONTENT_TOKEN, SELECTION_DIGEST);

		assertTrue(state.entries().isEmpty());
		assertTrue(Files.notExists(path));
		List<Path> aside;
		try (var leftovers = Files.list(path.getParent())) {
			aside = leftovers.filter(entry -> entry.getFileName().toString().startsWith(path.getFileName().toString() + ".corrupt-")).toList();
		}
		assertEquals(1, aside.size());
		assertTrue(Files.readString(aside.get(0)).contains("\"schemaVersion\": 2") || Files.readString(aside.get(0)).contains("\"schemaVersion\":2"));
	}

	/**
	 * The migration is an initialization step, so the legacy tree has to exist before the storage does: a reader that
	 * imported it would make every unlocked caller a writer, and two of those racing would drop each other's entries.
	 */
	@Test
	void legacyPerIdentityTreeIsImportedOnceAtInitialization() throws Exception {
		seedLegacyTree();
		ClientStorage storage = TestDataRoot.open(tempDir.resolve("game"), tempDir.resolve("data"));

		GeneratedCopyState state = GeneratedCopyState.read(storage, MODPACK_ID, CONTENT_TOKEN, SELECTION_DIGEST);

		assertEquals(List.of(new GeneratedCopyState.Entry("mods/nested.jar", HashUtils.sha1("nested"), 1)), state.entries());
		assertFalse(Files.exists(storage.generatedCopiesDirectory()), "The imported legacy tree is gone, evidence aside");
		assertTrue(Files.exists(storage.generatedCopiesFile()));
	}

	@Test
	void aLegacyTreeWithTwoSelectionsKeepsTheOneTheActivePointerCommittedUnder() throws Exception {
		SelectionIntent applied = new SelectionIntent(Set.of("main"));
		String appliedDigest = UpdateTransaction.digest(applied);
		seedLegacyTree();
		Path appliedFile = tempDir.resolve("game").resolve(StoragePaths.CLIENT_GENERATED_COPIES_DIR).resolve(MODPACK_ID).resolve(CONTENT_TOKEN).resolve(appliedDigest + ".json");
		Files.writeString(appliedFile, legacyDocument(MODPACK_ID, appliedDigest, HashUtils.sha1("applied"), "mods/applied.jar", 3), StandardCharsets.UTF_8);
		Path activeState = tempDir.resolve("game").resolve(StoragePaths.CLIENT_ACTIVE_STATE_FILE);
		Files.createDirectories(activeState.getParent());
		ClientStorageJsons.ClientGenerationStateFields state = new ClientStorageJsons.ClientGenerationStateFields();
		state.modpackId = MODPACK_ID;
		state.contentToken = CONTENT_TOKEN;
		state.ownershipLedger = OwnershipLedger.empty(MODPACK_ID).toFields();
		state.selectedGroups = List.copyOf(applied.requestedGroups());
		state.selectedCategories = List.of();
		state.excludedGroups = List.of();
		ConfigTools.writeAtomic(activeState, state);

		ClientStorage storage = TestDataRoot.open(tempDir.resolve("game"), tempDir.resolve("data"));

		assertEquals(List.of(new GeneratedCopyState.Entry("mods/applied.jar", HashUtils.sha1("applied"), 3)),
				GeneratedCopyState.read(storage, MODPACK_ID, CONTENT_TOKEN, appliedDigest).entries());
		assertTrue(GeneratedCopyState.read(storage, MODPACK_ID, CONTENT_TOKEN, SELECTION_DIGEST).entries().isEmpty());
		assertEquals(1, GeneratedCopyState.loadAll(storage).size());
	}

	@Test
	void theLegacyTreeImportDoesNotDropAnotherPacksEntry() throws Exception {
		seedLegacyTree();
		String otherSelection = HashUtils.sha1("other-selection");
		Path other = tempDir.resolve("game").resolve(StoragePaths.CLIENT_GENERATED_COPIES_DIR).resolve("def5678").resolve(CONTENT_TOKEN).resolve(otherSelection + ".json");
		Files.createDirectories(other.getParent());
		Files.writeString(other, legacyDocument("def5678", otherSelection, HashUtils.sha1("other-nested"), "mods/other.jar", 2), StandardCharsets.UTF_8);

		ClientStorage storage = TestDataRoot.open(tempDir.resolve("game"), tempDir.resolve("data"));

		assertEquals(2, GeneratedCopyState.loadAll(storage).size());
		assertEquals(List.of(new GeneratedCopyState.Entry("mods/other.jar", HashUtils.sha1("other-nested"), 2)),
				GeneratedCopyState.read(storage, "def5678", CONTENT_TOKEN, otherSelection).entries());
	}

	@Test
	void asideLeftoversNeverBlockTheOwnershipSweep() throws Exception {
		seedLegacyTree();
		Path generationDirectory = tempDir.resolve("game").resolve(StoragePaths.CLIENT_GENERATED_COPIES_DIR).resolve(MODPACK_ID).resolve(CONTENT_TOKEN);
		Files.createDirectories(generationDirectory);
		Files.writeString(generationDirectory.resolve(HashUtils.sha1("gone") + ".json.corrupt-1757000000000"), "{}", StandardCharsets.UTF_8);
		Files.writeString(generationDirectory.resolve(".state.json.leftover.tmp"), "{}", StandardCharsets.UTF_8);
		ClientStorage storage = TestDataRoot.open(tempDir.resolve("game"), tempDir.resolve("data"));

		ClientObjectStore.publishOwnership(storage);

		assertTrue(Files.exists(generationDirectory), "Set-aside evidence stays where it lived");
	}

	@Test
	void aLostIndexIsRebuiltForTheActiveGenerationAtBoot() throws Exception {
		ClientStorage storage = TestDataRoot.open(tempDir.resolve("game"), tempDir.resolve("data"));
		SelectedModpackTarget target = UpdateTestFixtures.target(storage, "mods/managed.jar", "mod", false, HashUtils.sha1("managed"), 1);
		Path bundle = storage.gamePath(ModpackPathPolicy.generatedBundlePath());
		Files.createDirectories(bundle.getParent());
		byte[] bundleBytes = "generated-bundle".getBytes(StandardCharsets.UTF_8);
		Files.write(bundle, bundleBytes);
		storage.writeActiveState(target.manifest().modpackId(), target.packTarget().contentToken(), target.document().ownershipLedger().toFields(), target.selection().intent());
		Files.writeString(storage.generatedCopiesFile(), "{\"schemaVersion\":2,\"states\":[]}", StandardCharsets.UTF_8);

		GeneratedCopyState.rebuildActive(storage);

		GeneratedCopyState rebuilt = GeneratedCopyState.read(storage, target.manifest().modpackId(), target.packTarget().contentToken(), UpdateTransaction.digest(target.selection().intent()));
		assertEquals(List.of(new GeneratedCopyState.Entry(ModpackPathPolicy.generatedBundlePath(), HashUtils.sha1(bundleBytes), bundleBytes.length)), rebuilt.entries());
		assertEquals(1, GeneratedCopyState.loadAll(storage).size(), "Only the active generation is rebuilt");
		SelectionIntent applied = new SelectionIntent(Set.of("main"), List.of(), List.of(), ClientPlatform.LINUX);
		GeneratedCopyState read = GeneratedCopyState.read(storage, target.manifest().modpackId(), target.packTarget().contentToken(), UpdateTransaction.digest(applied));
		assertEquals(rebuilt.entries(), read.entries(), "The rebuilt record answers to the selection the pointer committed under");
	}

	private void seedLegacyTree() throws IOException {
		Path legacy = tempDir.resolve("game").resolve(StoragePaths.CLIENT_GENERATED_COPIES_DIR).resolve(MODPACK_ID).resolve(CONTENT_TOKEN).resolve(SELECTION_DIGEST + ".json");
		Files.createDirectories(legacy.getParent());
		Files.writeString(legacy, legacyDocument(MODPACK_ID, SELECTION_DIGEST, HashUtils.sha1("nested"), "mods/nested.jar", 1), StandardCharsets.UTF_8);
	}

	private static String legacyDocument(String modpackId, String selectionDigest, String nestedHash, String logicalPath, long size) {
		return "{\"schemaVersion\":1,\"modpackId\":\"" + modpackId + "\",\"contentToken\":\"" + CONTENT_TOKEN + "\",\"selectionDigest\":\"" + selectionDigest
				+ "\",\"entries\":[{\"logicalPath\":\"" + logicalPath + "\",\"sha1\":\"" + nestedHash + "\",\"size\":" + size + "}]}";
	}
}
