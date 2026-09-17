package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.modpack.generation.Journal;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.storage.ObjectStoreMaintenance.ExpectedSizes;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;

class ClientObjectStoreTest {
	private static final String MODPACK_ID = "abc1234";

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
	void collectionKeepsMirrorReferencedBytesAndDeletesOnlyOrphans() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "generation-object".getBytes(StandardCharsets.UTF_8);
		String referenced = store(storage, bytes);
		String orphan = store(storage, "orphan");
		PackDocument record = TestPacks.document(manifest(referenced, bytes.length));
		TestPacks.stageGeneration(storage, record);
		storage.writeActiveState(MODPACK_ID, record.contentToken(), record.ownershipLedger().toFields());

		ClientObjectStore.CollectionResult result = ClientObjectStore.collectUnreachableObjects(storage, Set.of());

		assertEquals(1, result.deletedObjectCount());
		assertEquals("orphan".getBytes(StandardCharsets.UTF_8).length, result.deletedObjectBytes());
		assertTrue(Files.exists(storage.objectFile(referenced)));
		assertFalse(Files.exists(storage.objectFile(orphan)));
		assertEquals(2, result.after().validReferencedObjectCount(), "The generation object and its policy document stay referenced");
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
		TestPacks.stageGeneration(second, record);
		ClientObjectStore.publishOwnership(second);

		ClientObjectStore.CollectionResult result = ClientObjectStore.collectUnreachableObjects(first, Set.of());

		assertEquals(1, result.deletedObjectCount());
		assertTrue(Files.exists(first.objectFile(hash)));
		assertFalse(Files.exists(first.objectFile(orphan)));
	}

	@Test
	void collectionRetainsReceiptWhileItsInstallationIsUnavailable() throws Exception {
		Path sharedData = temporaryDirectory.resolve("shared-data");
		ClientStorage first = storage("first-game", sharedData);
		ClientStorage removed = storage("removed-game", sharedData);
		String hash = store(removed, "removed-instance-object");
		ClientObjectStore.publishOwnership(removed, Set.of(hash));
		FileTrees.delete(removed.gameDirectory());

		ClientObjectStore.CollectionResult result = ClientObjectStore.collectUnreachableObjects(first, Set.of());

		assertEquals(0, result.deletedObjectCount());
		assertTrue(Files.exists(first.objectFile(hash)));
	}

	@Test
	void historicalAndReplacedObjectsSurviveWhileTheMirrorNamesThem() throws Exception {
		ClientStorage storage = storage();
		byte[] activeBytes = "active-object".getBytes(StandardCharsets.UTF_8);
		byte[] historicalBytes = "historical-object".getBytes(StandardCharsets.UTF_8);
		String activeHash = store(storage, activeBytes);
		String historicalHash = store(storage, historicalBytes);
		String orphanHash = store(storage, "orphan");
		PackDocument historical = TestPacks.document(manifest(MODPACK_ID, historicalHash, historicalBytes.length));
		PackDocument active = TestPacks.document(manifest(MODPACK_ID, activeHash, activeBytes.length), historical.ownershipLedger(), TestPacks.CREATED.plusSeconds(1));
		TestPacks.stageGeneration(storage, historical);
		TestPacks.stageGeneration(storage, active);
		storage.writeActiveState(MODPACK_ID, active.contentToken(), active.ownershipLedger().toFields());

		ClientObjectStore.CollectionResult result = ClientObjectStore.collectUnreachableObjects(storage, Set.of());

		assertEquals(1, result.deletedObjectCount());
		assertTrue(Files.exists(storage.objectFile(activeHash)));
		assertTrue(Files.exists(storage.objectFile(historicalHash)), "The mirror's older entry still names the replaced object");
		assertTrue(Files.exists(storage.objectFile(active.policySha1())));
		assertFalse(Files.exists(storage.objectFile(orphanHash)));
	}

	@Test
	void collectionSetsAMalformedMirrorAsideAndRuns() throws Exception {
		ClientStorage storage = storage();
		String orphan = store(storage, "orphan");
		Files.createDirectories(storage.historyJournalFile(MODPACK_ID).getParent());
		Files.writeString(storage.historyJournalFile(MODPACK_ID), "{not a journal", StandardCharsets.UTF_8);

		// The malformed mirror is set aside as evidence, so it pins nothing and the collection runs over the rest.
		ClientObjectStore.CollectionResult result = ClientObjectStore.collectUnreachableObjects(storage, Set.of());
		assertEquals(1, result.deletedObjectCount());
		assertFalse(Files.exists(storage.objectFile(orphan)));
		assertTrue(Files.notExists(storage.historyJournalFile(MODPACK_ID)));
		try (var leftovers = Files.list(storage.historyJournalFile(MODPACK_ID).getParent())) {
			assertTrue(leftovers.anyMatch(path -> path.getFileName().toString().startsWith(storage.historyJournalFile(MODPACK_ID).getFileName() + ".corrupt-")));
		}
	}

	@Test
	void contradictoryMirrorSizeClaimsCannotFailTheSweep() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "contested-object".getBytes(StandardCharsets.UTF_8);
		String contested = store(storage, bytes);
		String orphan = store(storage, "orphan");
		// The first claim lies about the size, the second tells the truth: server-authored history, so neither may throw.
		writeMirror(storage, new JournalEntry.Change("mods/one.jar", null, 0, contested, bytes.length + 5),
				new JournalEntry.Change("mods/two.jar", null, 0, contested, bytes.length));

		assertTrue(ClientObjectStore.referencedHashes(storage).contains(contested));
		ClientObjectStore.CollectionResult result = ClientObjectStore.collectUnreachableObjects(storage, Set.of());

		assertEquals(1, result.deletedObjectCount());
		assertTrue(Files.exists(storage.objectFile(contested)), "The contested object stays reachable through the lying mirror");
		assertFalse(Files.exists(storage.objectFile(orphan)));
	}

	@Test
	void aContestedMirrorSizeStaysUnvouchedSoTheObjectMeasuresValid() throws Exception {
		ClientStorage storage = storage();
		byte[] bytes = "contested-object".getBytes(StandardCharsets.UTF_8);
		String contested = store(storage, bytes);
		writeMirror(storage, new JournalEntry.Change("mods/one.jar", null, 0, contested, bytes.length + 5),
				new JournalEntry.Change("mods/two.jar", null, 0, contested, bytes.length));

		// The contradiction demotes the size to unknown, so it is measured from the bytes: no false invalid count.
		ClientObjectStore.StorageReport report = ClientObjectStore.measure(storage);
		assertEquals(2, report.referencedObjectCount(), "The contested object and the entry's policy document");
		assertEquals(1, report.validReferencedObjectCount());
		assertEquals(0, report.invalidReferencedObjectCount());
	}

	@Test
	void historyClaimsFillUnknownHashesWithoutOverridingLocalReceipts() throws Exception {
		String local = HashUtils.sha1("local".getBytes(StandardCharsets.UTF_8));
		String unknown = HashUtils.sha1("unknown".getBytes(StandardCharsets.UTF_8));
		String fresh = HashUtils.sha1("fresh".getBytes(StandardCharsets.UTF_8));
		ExpectedSizes owned = new ExpectedSizes();
		owned.optional(local, 100, "client baseline");
		owned.optional(unknown, -1, "policy document");

		ClientObjectStore.mergeHistoryClaims(owned, new TreeMap<>(Map.of(local, 5L, unknown, 7L, fresh, 9L)));

		assertEquals(100L, owned.sizes().get(local), "A claim never overrides a locally verified receipt");
		assertEquals(-1L, owned.sizes().get(unknown), "A claim never turns a local unknown into a receipt");
		assertEquals(9L, owned.sizes().get(fresh));
	}

	@Test
	void refusesCollectionWhenObjectStoreContainsSymlink() throws Exception {
		ClientStorage storage = storage();
		Path target = temporaryDirectory.resolve("outside");
		Files.writeString(target, "outside", StandardCharsets.UTF_8);
		Files.createSymbolicLink(storage.objectsDirectory().resolve("not-an-object"), target);

		assertThrows(IOException.class, () -> ClientObjectStore.measure(storage));
		assertThrows(IOException.class, () -> ClientObjectStore.collectUnreachableObjects(storage, Set.of()));
		assertTrue(Files.exists(target));
	}

	@Test
	void normalizesObjectHashesAndRejectsInvalidPins() throws Exception {
		assertEquals("0123456789abcdef0123456789abcdef01234567", ClientObjectStore.normalizeHash("0123456789ABCDEF0123456789ABCDEF01234567"));
		assertThrows(IllegalArgumentException.class, () -> ClientObjectStore.normalizeHash("not-a-sha1"));
		ClientStorage storage = storage();
		assertThrows(IOException.class, () -> ClientObjectStore.collectUnreachableObjects(storage, Set.of("not-a-sha1")));
	}

	/** The receipt behind publishOwnership's per-commit cost: measured ~2ms warm for 200 entries; the assert is a structural tripwire, not a speed test. */
	@Test
	void referenceSweepStaysCheapOnATwoHundredEntryJournal() throws Exception {
		ClientStorage storage = storage();
		for (int generation = 0; generation < 200; generation++) {
			byte[] content = ("generation-" + generation).getBytes(StandardCharsets.UTF_8);
			String hash = store(storage, content);
			PackDocument record = TestPacks.document(TestPacks.manifest("generation " + generation, "config/gen-" + generation + ".txt", new String(content, StandardCharsets.UTF_8)));
			TestPacks.stageGeneration(storage, record);
			assertTrue(Files.exists(storage.objectFile(hash)));
		}

		long start = System.nanoTime();
		Set<String> referenced = ClientObjectStore.referencedHashes(storage);
		long sweepMillis = (System.nanoTime() - start) / 1_000_000;

		// 200 policy documents plus their change targets: the mirror alone pins ~400 objects, and the sweep walks
		// every mirror entry's JSON plus every overlay, baseline, and generated-copy file. Anything past the
		// measured ~2ms by orders of magnitude means the sweep became structural, not incremental.
		assertTrue(sweepMillis < 5_000, "The reference sweep took " + sweepMillis + "ms for a 200-entry journal");
		assertTrue(referenced.size() >= 400);
		System.out.println("Reference sweep over a 200-entry journal: " + sweepMillis + "ms, " + referenced.size() + " referenced hashes");
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

	/** Writes a hand-crafted mirror journal: the fixture for server-authored history that no honest staging would produce. */
	private static void writeMirror(ClientStorage storage, JournalEntry.Change... changes) throws Exception {
		Path fetched = Files.createTempFile(storage.gameDirectory(), "fetched-journal-", ".jsonl");
		Journal journal = Journal.open(fetched);
		journal.append(new JournalEntry(1, HashUtils.sha1("test-token".getBytes(StandardCharsets.UTF_8)), HashUtils.sha1("test-policy".getBytes(StandardCharsets.UTF_8)), TestPacks.CREATED, "Crafted",
				JournalEntry.NO_RESTORE, List.of(changes)));
		new JournalMirror(storage).replaceFrom(MODPACK_ID, fetched);
	}

	private static String store(ClientStorage storage, byte[] bytes) throws Exception {
		Path temporary = Files.createTempFile(storage.stagingDirectory(), "object-", ".tmp");
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
		GroupManifest.Group group = new GroupManifest.Group("", "", "General", true, false, new TreeSet<>(), new TreeSet<>(), Set.of(), new TreeMap<>(Map.of("mods/test.jar", file)));
		return new GroupManifest(modpackId, "", "", "", "", "", new TreeMap<>(Map.of("main", group)));
	}
}
