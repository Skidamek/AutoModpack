package pl.skidam.automodpack_core.utils.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.StorageJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.PreservationVault;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
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
		Files.writeString(storage.fileMetadataDirectory().resolve("cache.json"), "metadata", StandardCharsets.UTF_8);

		ClientObjectStore.StorageReport report = ClientObjectStore.measure(storage);

		assertEquals(2, report.objectCount());
		assertEquals(Set.of(referenced), ClientObjectStore.referencedHashes(storage));
		assertEquals(1, report.referencedObjectCount());
		assertEquals(1, report.validReferencedObjectCount());
		assertEquals(Files.size(storage.objectsDirectory().resolve(referenced)) + Files.size(storage.objectsDirectory().resolve(orphan)), report.objectBytes());
		assertTrue(report.metadataBytes() > 0);
		assertTrue(report.overlayBytes() > 0);
		assertTrue(report.referencedObjectCoverageRatio().orElseThrow() == 1.0);
		assertTrue(Files.exists(storage.objectsDirectory().resolve(orphan)));
	}

	@Test
	void retainsActiveGenerationAndDeletesOnlyVerifiedUnreachableObjects() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "generation-object".getBytes(StandardCharsets.UTF_8);
		String referenced = store(storage, bytes);
		String orphan = store(storage, "orphan");
		GenerationRecord record = GenerationRecord.create(manifest(referenced, bytes.length), null, Instant.parse("2026-08-08T00:00:00Z"), "notes");
		new ClientGenerationStore(storage).write(record);
		storage.writeActiveState(MODPACK_ID, record.metadata().generationId());

		ClientObjectStore.CollectionResult result = ClientObjectStore.collectUnreachableObjects(storage, Set.of(record.metadata().generationId()), Set.of());

		assertEquals(1, result.deletedObjectCount());
		assertEquals(bytes.length == 0 ? 0 : "orphan".getBytes(StandardCharsets.UTF_8).length, result.deletedObjectBytes());
		assertTrue(Files.exists(storage.objectsDirectory().resolve(referenced)));
		assertFalse(Files.exists(storage.objectsDirectory().resolve(orphan)));
		assertEquals(1, result.after().validReferencedObjectCount());
		assertTrue(result.after().objectBytes() < result.before().objectBytes());
		assertEquals(ClientObjectStore.CollectionStatus.COLLECTED, result.status());
	}

	@Test
	void sharedStoreCollectionRetainsObjectsOwnedByAnotherInstance() throws Exception {
		Path sharedData = temporaryDirectory.resolve("shared-data");
		ClientStorage first = storage("first-game", sharedData, true);
		ClientStorage second = storage("second-game", sharedData, true);
		byte[] bytes = "second-instance-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(second, bytes);
		String orphan = store(first, "shared-orphan");
		GenerationRecord record = GenerationRecord.create(manifest(hash, bytes.length), null, Instant.parse("2026-08-08T00:00:00Z"), "notes");
		new ClientGenerationStore(second).write(record);
		ClientObjectStore.publishOwnership(second);

		ClientObjectStore.CollectionResult result = ClientObjectStore.collectUnreachableObjects(first, Set.of(), Set.of());

		assertEquals(ClientObjectStore.CollectionStatus.COLLECTED, result.status());
		assertEquals(1, result.deletedObjectCount());
		assertTrue(Files.exists(first.objectsDirectory().resolve(hash)));
		assertFalse(Files.exists(first.objectsDirectory().resolve(orphan)));
	}

	@Test
	void pinsCachedObjectsReferencedByInstalledGenerationCatalogues() throws Exception {
		ClientStorage storage = storage();
		byte[] activeBytes = "active-object".getBytes(StandardCharsets.UTF_8);
		byte[] historicalBytes = "historical-object".getBytes(StandardCharsets.UTF_8);
		String activeHash = store(storage, activeBytes);
		String historicalHash = store(storage, historicalBytes);
		String orphanHash = store(storage, "orphan");
		GenerationRecord active = GenerationRecord.create(manifest(MODPACK_ID, activeHash, activeBytes.length), null, Instant.parse("2026-08-08T00:00:00Z"), "active");
		GenerationRecord historical = GenerationRecord.create(manifest(OTHER_MODPACK_ID, historicalHash, historicalBytes.length), null, Instant.parse("2026-08-07T00:00:00Z"), "historical");
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		generations.write(active);
		generations.write(historical);
		storage.writeActiveState(MODPACK_ID, active.metadata().generationId());

		assertThrows(IOException.class, () -> ClientObjectStore.collectUnreachableObjects(storage, Set.of(active.metadata().generationId()), Set.of()));
		assertTrue(Files.exists(storage.objectsDirectory().resolve(historicalHash)));
		assertTrue(Files.exists(storage.objectsDirectory().resolve(orphanHash)));

		ClientObjectStore.CollectionResult result = ClientObjectStore.collectUnreachableObjects(storage,
				Set.of(active.metadata().generationId(), historical.metadata().generationId()), Set.of());

		assertEquals(1, result.deletedObjectCount());
		assertTrue(Files.exists(storage.objectsDirectory().resolve(activeHash)));
		assertTrue(Files.exists(storage.objectsDirectory().resolve(historicalHash)));
		assertFalse(Files.exists(storage.objectsDirectory().resolve(orphanHash)));
	}

	@Test
	void refusesCollectionWhenGenerationMetadataIsMalformed() throws Exception {
		ClientStorage storage = storage();
		String orphan = store(storage, "orphan");
		String malformed = "0".repeat(40);
		Files.createDirectories(storage.generationDirectory(malformed));
		Files.writeString(storage.generationManifest(malformed), "{}", StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> ClientObjectStore.collectUnreachableObjects(storage, Set.of(), Set.of()));
		assertTrue(Files.exists(storage.objectsDirectory().resolve(orphan)));
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
		assertTrue(Files.exists(storage.objectsDirectory().resolve(hash)));

		PreservationVault.delete(storage, MODPACK_ID, claim.claimId());
		ClientObjectStore.collectUnreachableObjects(storage, Set.of(), Set.of());
		assertFalse(Files.exists(storage.objectsDirectory().resolve(hash)));
	}

	private ClientStorage storage() throws Exception {
		return storage("game", temporaryDirectory.resolve("data"), false);
	}

	private ClientStorage storage(String gameName, Path dataDirectory, boolean shared) throws Exception {
		Path game = temporaryDirectory.resolve(gameName);
		Files.createDirectories(game.resolve("automodpack"));
		StorageJsons.DataRootFields dataRoot = new StorageJsons.DataRootFields();
		dataRoot.root = dataDirectory.toString();
		dataRoot.shared = shared;
		ConfigTools.writeAtomic(game.resolve("automodpack/data-root.json"), dataRoot);
		ClientStorage storage = ClientStorage.open(game);
		return storage;
	}

	private static String store(ClientStorage storage, String text) throws Exception {
		return store(storage, text.getBytes(StandardCharsets.UTF_8));
	}

	private static String store(ClientStorage storage, byte[] bytes) throws Exception {
		Path temporary = Files.createTempFile(storage.incomingDirectory(), "object-", ".tmp");
		Files.write(temporary, bytes);
		String hash = HashUtils.getHash(temporary);
		Files.move(temporary, storage.objectsDirectory().resolve(hash), StandardCopyOption.REPLACE_EXISTING);
		return hash;
	}

	private static GroupManifest manifest(String hash, long size) {
		return manifest(MODPACK_ID, hash, size);
	}

	private static GroupManifest manifest(String modpackId, String hash, long size) {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(size, "mod", false, false, hash, null);
		GroupManifest.Group group = new GroupManifest.Group("", "", "", "", true, false, new TreeSet<>(), new TreeSet<>(), Set.of(), new TreeMap<>(Map.of("mods/test.jar", file)));
		return new GroupManifest(modpackId, "", "", "", "", "", new TreeMap<>(Map.of("main", group)));
	}
}
