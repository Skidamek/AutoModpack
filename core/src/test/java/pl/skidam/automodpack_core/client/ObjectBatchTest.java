package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.loader.FileInspection;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

class ObjectBatchTest {

	private static byte[] bytes(int size, long seed) {
		byte[] data = new byte[size];
		new Random(seed).nextBytes(data);
		return data;
	}

	private static QueuedDownload task(String sha1, long size) {
		return new QueuedDownload(new FileInspection.HashPathPair(sha1, Path.of(sha1 + ".jar")), Path.of(sha1 + ".jar"), List.of(), null, "mods", size, 0, () -> {}, category -> {});
	}

	private static QueuedDownload downloaded(ObjectPromotion promotion, DataRootResolver.Layout layout, byte[] data) throws Exception {
		String sha1 = HashUtils.sha1(data);
		QueuedDownload task = task(sha1, data.length);
		Path partial = promotion.preparePartial(sha1, task);
		try (OutputStream out = PartialResume.writer(partial, data.length, 0)) {
			out.write(data);
		}
		try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
			assertTrue(promotion.promote(sha1, task, partial, cache));
		}
		return task;
	}

	private static List<Path> staged(DataRootResolver.Layout layout) throws Exception {
		try (Stream<Path> files = Files.walk(layout.stagingDirectory())) {
			return files.filter(Files::isRegularFile).toList();
		}
	}

	@Test
	void heldObjectsStayOutOfTheStoreUntilTheBatchSyncPublishesThem(@TempDir Path root) throws Exception {
		DataRootResolver.Layout layout = new DataRootResolver.Layout(root);
		ObjectBatch batch = new ObjectBatch(layout);
		ObjectPromotion promotion = new ObjectPromotion(layout, batch);
		List<byte[]> objects = List.of(bytes(10_000, 1), bytes(NetUtils.WIRE_CHUNK_BYTES, 2), bytes(NetUtils.WIRE_CHUNK_BYTES * 2 + 9, 3), new byte[0]);
		for (byte[] data : objects) {
			QueuedDownload task = downloaded(promotion, layout, data);
			assertNull(task.partialFile, "the batch owns the slice directory once the object is held");
		}
		assertEquals(objects.size(), batch.size());
		try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
			for (byte[] data : objects) {
				String sha1 = HashUtils.sha1(data);
				assertFalse(Files.exists(layout.objectFile(sha1)), "nothing that reads the store can see an object before its sync");
				assertNull(cache.getHashOrNull(layout.objectFile(sha1)), "no cache record names an object before its sync");
			}
		}

		assertEquals(List.of(), batch.flush());

		assertEquals(0, batch.size());
		try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
			for (byte[] data : objects) {
				String sha1 = HashUtils.sha1(data);
				assertArrayEquals(data, Files.readAllBytes(layout.objectFile(sha1)));
				assertTrue(cache.matchesImmutable(layout.objectFile(sha1), data.length, sha1));
			}
		}
		assertEquals(List.of(), staged(layout), "the flush leaves no held file or slice directory behind");
	}

	@Test
	void aCrashBeforeTheBatchSyncLeavesOnlyUntrustedHeldFilesThatAreHashedAgain(@TempDir Path root) throws Exception {
		DataRootResolver.Layout layout = new DataRootResolver.Layout(root);
		byte[] intact = bytes(200_000, 4);
		byte[] torn = bytes(NetUtils.WIRE_CHUNK_BYTES + 1234, 5);
		ObjectPromotion crashed = new ObjectPromotion(layout, new ObjectBatch(layout));
		downloaded(crashed, layout, intact);
		downloaded(crashed, layout, torn);
		// The process dies before joinAll flushes. A power cut can keep a held file's name and size but not its bytes.
		Path tornHeld = PartialResume.heldFiles(PartialResume.directory(layout.stagingDirectory(), HashUtils.sha1(torn))).get(0);
		Files.write(tornHeld, new byte[torn.length]);

		ObjectBatch next = new ObjectBatch(layout);
		ObjectPromotion rebooted = new ObjectPromotion(layout, next);
		try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
			String tornSha1 = HashUtils.sha1(torn);
			assertFalse(rebooted.adoptHeld(tornSha1, task(tornSha1, torn.length), cache), "torn held bytes fail their hash and are downloaded again");
			assertFalse(Files.exists(layout.objectFile(tornSha1)), "a torn object never reaches the store");
			assertEquals(List.of(), PartialResume.heldFiles(PartialResume.directory(layout.stagingDirectory(), tornSha1)), "the torn copy is dropped");

			String intactSha1 = HashUtils.sha1(intact);
			assertTrue(rebooted.adoptHeld(intactSha1, task(intactSha1, intact.length), cache), "intact held bytes still pass their hash and need no download");
			assertArrayEquals(intact, Files.readAllBytes(layout.objectFile(intactSha1)));
			assertTrue(cache.matchesImmutable(layout.objectFile(intactSha1), intact.length, intactSha1));
			assertFalse(Files.exists(PartialResume.directory(layout.stagingDirectory(), intactSha1)));
		}
		assertEquals(0, next.size(), "an adopted object is forced into the store at once rather than held again");
	}

	@Test
	void adoptionNeverTakesAnObjectThisRunStillHolds(@TempDir Path root) throws Exception {
		DataRootResolver.Layout layout = new DataRootResolver.Layout(root);
		ObjectBatch batch = new ObjectBatch(layout);
		ObjectPromotion promotion = new ObjectPromotion(layout, batch);
		byte[] data = bytes(5_000, 6);
		String sha1 = HashUtils.sha1(data);
		downloaded(promotion, layout, data);
		try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
			assertFalse(promotion.adoptHeld(sha1, task(sha1, data.length), cache));
		}
		assertEquals(List.of(), batch.flush());
		assertArrayEquals(data, Files.readAllBytes(layout.objectFile(sha1)));
	}

	@Test
	void aHeldObjectLostBeforeTheFlushFailsItsTaskUnlessTheStoreAlreadyHasIt(@TempDir Path root) throws Exception {
		DataRootResolver.Layout layout = new DataRootResolver.Layout(root);
		ObjectBatch batch = new ObjectBatch(layout);
		ObjectPromotion promotion = new ObjectPromotion(layout, batch);
		byte[] won = bytes(7_000, 7);
		byte[] lost = bytes(8_000, 8);
		downloaded(promotion, layout, won);
		QueuedDownload lostTask = downloaded(promotion, layout, lost);
		for (byte[] data : List.of(won, lost))
			for (Path held : PartialResume.heldFiles(PartialResume.directory(layout.stagingDirectory(), HashUtils.sha1(data)))) Files.delete(held);
		// Another instance published the first object meanwhile; nobody published the second.
		Path wonObject = layout.objectFile(HashUtils.sha1(won));
		Files.createDirectories(wonObject.getParent());
		Files.write(wonObject, won);

		List<QueuedDownload> failed = batch.flush();

		assertEquals(List.of(lostTask), failed);
		assertEquals(DownloadManager.FailureCategory.LOCAL_STORAGE, lostTask.lastFailureCategory);
		assertFalse(Files.exists(layout.objectFile(HashUtils.sha1(lost))));
		assertEquals(List.of(), staged(layout));
	}

	@Test
	void aMismatchIsRejectedBeforeAnythingIsHeld(@TempDir Path root) throws Exception {
		DataRootResolver.Layout layout = new DataRootResolver.Layout(root);
		ObjectBatch batch = new ObjectBatch(layout);
		ObjectPromotion promotion = new ObjectPromotion(layout, batch);
		for (int size : new int[]{3_000, NetUtils.WIRE_CHUNK_BYTES + 3}) {
			byte[] advertised = bytes(size, size);
			byte[] received = advertised.clone();
			received[0] ^= 1;
			String sha1 = HashUtils.sha1(advertised);
			QueuedDownload task = task(sha1, size);
			Path partial = promotion.preparePartial(sha1, task);
			try (OutputStream out = PartialResume.writer(partial, size, 0)) {
				out.write(received);
			}
			try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
				assertFalse(promotion.promote(sha1, task, partial, cache));
			}
			assertEquals(DownloadManager.FailureCategory.REMOTE_SOURCE, task.lastFailureCategory);
		}
		assertEquals(0, batch.size());
		assertEquals(List.of(), staged(layout));
	}
}
