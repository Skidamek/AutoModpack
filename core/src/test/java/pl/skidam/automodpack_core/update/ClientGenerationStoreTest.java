package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.utils.HashUtils;

class ClientGenerationStoreTest {
	private static final String FIRST_PACK = "abc1234";
	private static final String SECOND_PACK = "def5678";

	@TempDir
	Path temporaryDirectory;

	@Test
	void reconstructsTheActiveTargetFromMirrorCasAndActiveState() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "projection-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);

		assertTrue(new ClientGenerationStore(storage).activeDocument().isEmpty());

		storage.writeActiveState(FIRST_PACK, document.contentToken(), document.ownershipLedger().toFields());
		new ClientSelectionStore(storage.selectionFile()).compareAndSet(FIRST_PACK, null, new SelectionIntent(Set.of("main")));
		SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.LINUX).orElseThrow();

		assertEquals(document, target.document());
		assertEquals(new SelectionIntent(Set.of("main")), target.selection().intent());
		assertEquals(document.contentToken(), target.flatTarget().contentToken);
		assertEquals(document.ownershipLedger(), target.document().ownershipLedger());
	}

	@Test
	void reconstructsTheActiveDefaultTargetWithoutStoredSelection() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "default-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		storage.writeActiveState(FIRST_PACK, document.contentToken(), document.ownershipLedger().toFields());

		SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.LINUX).orElseThrow();

		assertEquals(document, target.document());
		assertNull(target.expectedPriorIntent());
		assertEquals(Set.of("main"), target.selection().selectedGroups());
	}

	@Test
	void replaysTheExactLedgerForAFullyWitnessedHistory() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), Instant.parse("2026-01-01T00:00:00Z"));
		String secondHash = store(storage, "second-object");
		PackDocument second = document(FIRST_PACK, secondHash, Files.size(storage.objectFile(secondHash)), Instant.parse("2026-01-02T00:00:00Z"), first);
		TestPacks.stageGeneration(storage, first);
		TestPacks.stageGeneration(storage, second);
		storage.writeActiveState(FIRST_PACK, first.contentToken(), first.ownershipLedger().toFields());

		PackDocument newest = new ClientGenerationStore(storage).newestDocument(FIRST_PACK);
		assertEquals(second.contentToken(), newest.contentToken());
		assertEquals(second.ownershipLedger(), newest.ownershipLedger());
	}

	@Test
	void reconstructionFailsLoudlyWhenThePolicyObjectIsMissing() throws Exception {
		ClientStorage storage = storage();
		String hash = store(storage, "projection-object");
		PackDocument document = document(FIRST_PACK, hash, Files.size(storage.objectFile(hash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, document);
		storage.writeActiveState(FIRST_PACK, document.contentToken(), document.ownershipLedger().toFields());
		Files.delete(storage.objectFile(document.policySha1()));

		assertThrows(IOException.class, () -> new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.LINUX));
	}

	@Test
	void forgetModpackDeletesOnlyTheInactivePacksRetainedState() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		String secondHash = store(storage, "second-object");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), TestPacks.CREATED);
		PackDocument second = document(SECOND_PACK, secondHash, Files.size(storage.objectFile(secondHash)), TestPacks.CREATED);
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		TestPacks.stageGeneration(storage, first);
		TestPacks.stageGeneration(storage, second);
		new ClientSelectionStore(storage.selectionFile()).compareAndSet(FIRST_PACK, null, new SelectionIntent(Set.of("main")));
		storage.writeActiveState(SECOND_PACK, second.contentToken(), second.ownershipLedger().toFields());
		Path overlay = storage.overlayFile(FIRST_PACK, "config/options.txt");
		Files.createDirectories(overlay.getParent());
		Files.writeString(overlay, "edit", StandardCharsets.UTF_8);
		Files.createDirectories(storage.connectionDirectory(FIRST_PACK));
		Files.writeString(storage.connectionDirectory(FIRST_PACK).resolve("connection.json"), "{}", StandardCharsets.UTF_8);

		generations.forgetModpack(FIRST_PACK);

		assertEquals(List.of(SECOND_PACK), generations.installedPackIds());
		assertFalse(Files.exists(storage.overlayDirectory(FIRST_PACK)));
		assertFalse(Files.exists(storage.connectionDirectory(FIRST_PACK)));
		assertTrue(new ClientSelectionStore(storage.selectionFile()).get(FIRST_PACK).isEmpty());
		assertFalse(Files.exists(storage.objectFile(firstHash)));
		assertTrue(Files.exists(storage.objectFile(secondHash)));
		assertNotNull(generations.newestDocument(SECOND_PACK));
		assertEquals(second.contentToken(), storage.readActiveState().contentToken);
		assertThrows(IOException.class, () -> generations.forgetModpack(SECOND_PACK));
	}

	@Test
	void listingSkipsPacksWhoseGenerationCannotBeReconstructed() throws Exception {
		ClientStorage storage = storage();
		String firstHash = store(storage, "first-object");
		String secondHash = store(storage, "second-object");
		PackDocument first = document(FIRST_PACK, firstHash, Files.size(storage.objectFile(firstHash)), TestPacks.CREATED);
		PackDocument second = document(SECOND_PACK, secondHash, Files.size(storage.objectFile(secondHash)), TestPacks.CREATED);
		TestPacks.stageGeneration(storage, first);
		TestPacks.stageGeneration(storage, second);
		Files.delete(storage.objectFile(first.policySha1()));

		assertEquals(List.of(FIRST_PACK, SECOND_PACK), new ClientGenerationStore(storage).installedPackIds());
		assertThrows(IOException.class, () -> new ClientGenerationStore(storage).newestDocument(FIRST_PACK));
		assertEquals(second, new ClientGenerationStore(storage).newestDocument(SECOND_PACK));
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	private static PackDocument document(String modpackId, String hash, long size, Instant createdAt) {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(size, "mod", false, hash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "", "", true, true, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("mods/test.jar", file)));
		GroupManifest manifest = new GroupManifest(modpackId, "Test", "", "", "", "", new TreeMap<>(Map.of("main", group)));
		return PackDocument.create(manifest, TestPacks.policySha1(manifest), createdAt, null);
	}

	private static PackDocument document(String modpackId, String hash, long size, Instant createdAt, PackDocument parent) {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(size, "mod", false, hash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "", "", true, true, new TreeSet<>(), new TreeSet<>(), Set.of(),
				new TreeMap<>(Map.of("mods/test.jar", file)));
		GroupManifest manifest = new GroupManifest(modpackId, "Test", "", "", "", "", new TreeMap<>(Map.of("main", group)));
		return PackDocument.create(manifest, TestPacks.policySha1(manifest), createdAt, parent.ownershipLedger());
	}

	private static String store(ClientStorage storage, String content) throws IOException {
		Path temporary = Files.createTempFile(storage.objectsDirectory(), ".object-", ".tmp");
		Files.writeString(temporary, content, StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(temporary);
		Path destination = storage.objectFile(hash);
		Files.createDirectories(destination.getParent());
		Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
		return hash;
	}
}
