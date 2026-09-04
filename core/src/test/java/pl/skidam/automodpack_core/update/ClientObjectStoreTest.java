package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;

class ClientObjectStoreTest {
	private static final String MODPACK_ID = "abc1234";
	private static final String OTHER_MODPACK_ID = "def5678";

	@TempDir
	Path temporaryDirectory;

	@Test
	void reportsCasReferencesAndAdjacentStateWithoutDeleting() throws Exception {
		ClientStorage storage = storage();
		String referenced = store(storage, "referenced");
		String orphan = store(storage, "orphan");
		Files.createDirectories(storage.overlayFile(MODPACK_ID, "config/options.txt").getParent());
		Files.writeString(storage.overlayFile(MODPACK_ID, "config/options.txt"), "referenced", StandardCharsets.UTF_8);
		Files.writeString(storage.fileCacheDirectory().resolve("cache.json"), "metadata", StandardCharsets.UTF_8);

		ClientObjectStore.StorageReport report = ClientObjectStore.measure(storage);

		assertEquals(2, report.objectCount());
		assertEquals(Set.of(referenced), ClientObjectStore.referencedHashes(storage));
		assertEquals(1, report.referencedObjectCount());
		assertEquals(1, report.validReferencedObjectCount());
		assertEquals(Files.size(storage.objectFile(referenced)) + Files.size(storage.objectFile(orphan)), report.objectBytes());
		assertTrue(report.metadataBytes() > 0);
		assertTrue(report.overlayBytes() > 0);
		assertTrue(report.referencedObjectCoverageRatio().orElseThrow() == 1.0);
		assertTrue(Files.exists(storage.objectFile(orphan)));
	}

	@Test
	void retainsActiveGenerationAndDeletesOnlyVerifiedUnreachableObjects() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "generation-object".getBytes(StandardCharsets.UTF_8);
		String referenced = store(storage, bytes);
		String orphan = store(storage, "orphan");
		PackDocument record = TestPacks.document(manifest(referenced, bytes.length));
		new ClientGenerationStore(storage).write(record);
		storage.writeActiveState(MODPACK_ID, record.contentToken());

		ClientObjectStore.CollectionResult result = ClientObjectStore.collectUnreachableObjects(storage, Set.of(record.contentToken()), Set.of());

		assertEquals(1, result.deletedObjectCount());
		assertEquals(bytes.length == 0 ? 0 : "orphan".getBytes(StandardCharsets.UTF_8).length, result.deletedObjectBytes());
		assertTrue(Files.exists(storage.objectFile(referenced)));
		assertFalse(Files.exists(storage.objectFile(orphan)));
		assertEquals(1, result.after().validReferencedObjectCount());
		assertTrue(result.after().objectBytes() < result.before().objectBytes());
	}

	@Test
	void sharedStoreCollectionRetainsObjectsOwnedByAnotherInstance() throws Exception {
		Path sharedData = temporaryDirectory.resolve("shared-data");
		ClientStorage first = storage("first-game", sharedData);
		ClientStorage second = storage("second-game", sharedData);
		byte[] bytes = "second-instance-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(second, bytes);
		String orphan = store(first, "shared-orphan");
		PackDocument record = TestPacks.document(manifest(hash, bytes.length));
		new ClientGenerationStore(second).write(record);
		ClientObjectStore.publishOwnership(second);

		ClientObjectStore.CollectionResult result = ClientObjectStore.collectUnreachableObjects(first, Set.of(), Set.of());

		assertEquals(1, result.deletedObjectCount());
		assertTrue(Files.exists(first.objectFile(hash)));
		assertFalse(Files.exists(first.objectFile(orphan)));
	}

	@Test
	void copiedInstallationsGetANewOwnerIdentity() throws Exception {
		Path sharedData = temporaryDirectory.resolve("shared-data");
		ClientStorage original = storage("original-game", sharedData);
		ClientStorage clone = storage("cloned-game", sharedData);
		assertNotEquals(original.dataLocation().ownerId(), clone.dataLocation().ownerId());
	}

	@Test
	void collectionRetainsReceiptWhileItsInstallationIsUnavailable() throws Exception {
		Path sharedData = temporaryDirectory.resolve("shared-data");
		ClientStorage first = storage("first-game", sharedData);
		ClientStorage removed = storage("removed-game", sharedData);
		String hash = store(removed, "removed-instance-object");
		ClientObjectStore.publishOwnership(removed, Set.of(hash));
		FileTrees.delete(removed.gameDirectory());

		ClientObjectStore.CollectionResult result = ClientObjectStore.collectUnreachableObjects(first, Set.of(), Set.of());

		assertEquals(0, result.deletedObjectCount());
		assertTrue(Files.exists(first.objectFile(hash)));
	}

	@Test
	void pinsCachedObjectsReferencedByInstalledGenerationCatalogues() throws Exception {
		ClientStorage storage = storage();
		byte[] activeBytes = "active-object".getBytes(StandardCharsets.UTF_8);
		byte[] historicalBytes = "historical-object".getBytes(StandardCharsets.UTF_8);
		String activeHash = store(storage, activeBytes);
		String historicalHash = store(storage, historicalBytes);
		String orphanHash = store(storage, "orphan");
		PackDocument active = TestPacks.document(manifest(MODPACK_ID, activeHash, activeBytes.length));
		PackDocument historical = TestPacks.document(manifest(OTHER_MODPACK_ID, historicalHash, historicalBytes.length));
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(active);
		generations.write(historical);
		storage.writeActiveState(MODPACK_ID, active.contentToken());

		assertThrows(IOException.class, () -> ClientObjectStore.collectUnreachableObjects(storage, Set.of(active.contentToken()), Set.of()));
		assertTrue(Files.exists(storage.objectFile(historicalHash)));
		assertTrue(Files.exists(storage.objectFile(orphanHash)));

		ClientObjectStore.CollectionResult result = ClientObjectStore.collectUnreachableObjects(storage,
				Set.of(active.contentToken(), historical.contentToken()), Set.of());

		assertEquals(1, result.deletedObjectCount());
		assertTrue(Files.exists(storage.objectFile(activeHash)));
		assertTrue(Files.exists(storage.objectFile(historicalHash)));
		assertFalse(Files.exists(storage.objectFile(orphanHash)));
	}

	@Test
	void refusesCollectionWhenGenerationMetadataIsMalformed() throws Exception {
		ClientStorage storage = storage();
		String orphan = store(storage, "orphan");
		String malformed = "0".repeat(40);
		Files.createDirectories(storage.generationDirectory(malformed));
		Files.writeString(storage.generationManifest(malformed), "{}", StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> ClientObjectStore.collectUnreachableObjects(storage, Set.of(), Set.of()));
		assertTrue(Files.exists(storage.objectFile(orphan)));
	}

	@Test
	void refusesCollectionWhenObjectStoreContainsSymlink() throws Exception {
		ClientStorage storage = storage();
		Path target = temporaryDirectory.resolve("outside");
		Files.writeString(target, "outside", StandardCharsets.UTF_8);
		Files.createSymbolicLink(storage.objectsDirectory().resolve("not-an-object"), target);

		assertThrows(IOException.class, () -> ClientObjectStore.measure(storage));
		assertThrows(IOException.class, () -> ClientObjectStore.collectUnreachableObjects(storage, Set.of(), Set.of()));
		assertTrue(Files.exists(target));
	}

	@Test
	void normalizesObjectHashesAndRejectsInvalidPins() throws Exception {
		assertEquals("0123456789abcdef0123456789abcdef01234567", ClientObjectStore.normalizeHash("0123456789ABCDEF0123456789ABCDEF01234567"));
		assertThrows(IllegalArgumentException.class, () -> ClientObjectStore.normalizeHash("not-a-sha1"));
		ClientStorage storage = storage();
		assertThrows(IOException.class, () -> ClientObjectStore.collectUnreachableObjects(storage, Set.of(), Set.of("not-a-sha1")));
	}

	@Test
	void preservationClaimsPinObjectsUntilExplicitDeletion() throws Exception {
		ClientStorage storage = storage();
		Path source = storage.gamePath("config/removed.txt");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "preserved", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(source);
		PreservationVault.Claim claim = PreservationVault.preserve(storage, MODPACK_ID, "a".repeat(40), PreservationVault.Reason.SERVER_REMOVAL, Root.GAME_DIR,
				"config/removed.txt", hash, Files.size(source));

		ClientObjectStore.collectUnreachableObjects(storage, Set.of(), Set.of());
		assertTrue(Files.exists(storage.objectFile(hash)));

		PreservationVault.delete(storage, MODPACK_ID, claim.claimId());
		ClientObjectStore.collectUnreachableObjects(storage, Set.of(), Set.of());
		assertFalse(Files.exists(storage.objectFile(hash)));
	}

	private ClientStorage storage() throws Exception {
		return storage("game", temporaryDirectory.resolve("data"));
	}

	private ClientStorage storage(String gameName, Path dataDirectory) throws Exception {
		return TestDataRoot.open(temporaryDirectory.resolve(gameName), dataDirectory);
	}

	private static String store(ClientStorage storage, String text) throws Exception {
		return store(storage, text.getBytes(StandardCharsets.UTF_8));
	}

	private static String store(ClientStorage storage, byte[] bytes) throws Exception {
		Path temporary = Files.createTempFile(storage.incomingDirectory(), "object-", ".tmp");
		Files.write(temporary, bytes);
		String hash = HashUtils.getHash(temporary);
		Path destination = storage.objectFile(hash);
		Files.createDirectories(destination.getParent());
		Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
		return hash;
	}

	private static GroupManifest manifest(String hash, long size) {
		return manifest(MODPACK_ID, hash, size);
	}

	private static GroupManifest manifest(String modpackId, String hash, long size) {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(size, "mod", false, hash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "", "", true, false, new TreeSet<>(), new TreeSet<>(), Set.of(), new TreeMap<>(Map.of("mods/test.jar", file)));
		return new GroupManifest(modpackId, "", "", "", "", "", new TreeMap<>(Map.of("main", group)));
	}
}
