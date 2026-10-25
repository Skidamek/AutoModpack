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
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.cache.FileCache;

class ObjectPromotionTest {

	private static byte[] bytes(int size, long seed) {
		byte[] data = new byte[size];
		new Random(seed).nextBytes(data);
		return data;
	}

	private static QueuedDownload task(String sha1, long size) {
		return new QueuedDownload(new FileInspection.HashPathPair(sha1, Path.of("mod.jar")), Path.of("mod.jar"), List.of(), null, "mods", size, 0, () -> {}, category -> {});
	}

	private static Path stage(ObjectPromotion promotion, QueuedDownload task, byte[] data) throws Exception {
		Path partial = promotion.preparePartial(task.key.hash(), task);
		try (OutputStream out = PartialResume.writer(partial, data.length, 0)) {
			out.write(data);
		}
		assertTrue(PartialResume.complete(partial, data.length));
		return partial;
	}

	private static List<Path> stagingLeftovers(DataRootResolver.Layout layout) throws Exception {
		try (Stream<Path> files = Files.list(layout.stagingDirectory())) {
			return files.toList();
		}
	}

	private static void assertPublished(DataRootResolver.Layout layout, String sha1, byte[] data, FileCache cache) throws Exception {
		Path object = layout.objectFile(sha1);
		assertArrayEquals(data, Files.readAllBytes(object));
		assertTrue(cache.matchesImmutable(object, data.length, sha1), "the store object carries a cache record for its advertised hash");
		assertEquals(List.of(), stagingLeftovers(layout), "promotion leaves neither the slice directory nor a private temporary behind");
	}

	@Test
	void aOneTileObjectIsMovedIntoTheStoreWithoutACopy(@TempDir Path root) throws Exception {
		DataRootResolver.Layout layout = new DataRootResolver.Layout(root);
		ObjectPromotion promotion = new ObjectPromotion(layout);
		byte[] data = bytes(300_000, 1);
		String sha1 = HashUtils.sha1(data);
		QueuedDownload task = task(sha1, data.length);
		Path partial = stage(promotion, task, data);
		Object tileKey = Files.readAttributes(PartialResume.sliceFile(partial, 0), "basic:fileKey").get("fileKey");

		try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
			assertTrue(promotion.promote(sha1, task, partial, cache));
			assertPublished(layout, sha1, data, cache);
		}
		if (tileKey != null) assertEquals(tileKey, Files.readAttributes(layout.objectFile(sha1), "basic:fileKey").get("fileKey"), "the store object is the downloaded tile itself, not a second copy of its bytes");
		assertNull(task.partialFile);
	}

	@Test
	void aFullFourMebibyteObjectIsStillOneTile(@TempDir Path root) throws Exception {
		DataRootResolver.Layout layout = new DataRootResolver.Layout(root);
		ObjectPromotion promotion = new ObjectPromotion(layout);
		byte[] data = bytes(NetUtils.WIRE_CHUNK_BYTES, 2);
		String sha1 = HashUtils.sha1(data);
		QueuedDownload task = task(sha1, data.length);
		Path partial = stage(promotion, task, data);
		try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
			assertTrue(promotion.promote(sha1, task, partial, cache));
			assertPublished(layout, sha1, data, cache);
		}
	}

	@Test
	void aMultiTileObjectIsHashedWhileItIsStitched(@TempDir Path root) throws Exception {
		DataRootResolver.Layout layout = new DataRootResolver.Layout(root);
		ObjectPromotion promotion = new ObjectPromotion(layout);
		byte[] data = bytes(NetUtils.WIRE_CHUNK_BYTES * 2 + 12_345, 3);
		String sha1 = HashUtils.sha1(data);
		QueuedDownload task = task(sha1, data.length);
		Path partial = stage(promotion, task, data);
		try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
			assertTrue(promotion.promote(sha1, task, partial, cache));
			assertPublished(layout, sha1, data, cache);
		}
	}

	@Test
	void aResumedTransferPromotesTheSameBytesOnBothPaths(@TempDir Path root) throws Exception {
		DataRootResolver.Layout layout = new DataRootResolver.Layout(root);
		ObjectPromotion promotion = new ObjectPromotion(layout);
		for (int size : new int[]{1_000_000, NetUtils.WIRE_CHUNK_BYTES + 777_777}) {
			byte[] data = bytes(size, size);
			String sha1 = HashUtils.sha1(data);
			QueuedDownload task = task(sha1, data.length);
			Path partial = promotion.preparePartial(sha1, task);
			int cut = size / 3;
			// The first take dies mid-tile; the resume continues from the bytes already on disk.
			try (OutputStream out = PartialResume.writer(partial, size, 0)) {
				out.write(data, 0, cut);
			}
			assertFalse(PartialResume.complete(partial, size));
			long from = PartialResume.nextByte(partial, size);
			assertEquals(cut, from);
			try (OutputStream out = PartialResume.writer(partial, size, from)) {
				out.write(data, (int) from, size - (int) from);
			}
			try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
				assertTrue(promotion.promote(sha1, task, partial, cache), "size " + size);
				assertPublished(layout, sha1, data, cache);
			}
		}
	}

	@Test
	void mismatchedBytesAreNeverPublishedAndLeaveNothingBehind(@TempDir Path root) throws Exception {
		DataRootResolver.Layout layout = new DataRootResolver.Layout(root);
		ObjectPromotion promotion = new ObjectPromotion(layout);
		for (int size : new int[]{4096, NetUtils.WIRE_CHUNK_BYTES * 2 + 1}) {
			byte[] advertised = bytes(size, 10 + size);
			byte[] received = advertised.clone();
			received[size - 1] ^= 1;
			String sha1 = HashUtils.sha1(advertised);
			QueuedDownload task = task(sha1, size);
			Path partial = stage(promotion, task, received);
			try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
				assertFalse(promotion.promote(sha1, task, partial, cache), "size " + size);
			}
			assertEquals(DownloadManager.FailureCategory.REMOTE_SOURCE, task.lastFailureCategory);
			assertFalse(Files.exists(layout.objectFile(sha1)), "unverified bytes never appear under a hash name");
			assertNull(task.partialFile, "a mismatched partial is dropped so the retry starts from zero");
			assertEquals(List.of(), stagingLeftovers(layout));
		}
	}

	@Test
	void anIncompletePartialIsNotPromoted(@TempDir Path root) throws Exception {
		DataRootResolver.Layout layout = new DataRootResolver.Layout(root);
		ObjectPromotion promotion = new ObjectPromotion(layout);
		byte[] data = bytes(100_000, 4);
		String sha1 = HashUtils.sha1(data);
		QueuedDownload task = task(sha1, data.length);
		Path partial = promotion.preparePartial(sha1, task);
		try (OutputStream out = PartialResume.writer(partial, data.length, 0)) {
			out.write(data, 0, 50_000);
		}
		try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
			assertFalse(promotion.promote(sha1, task, partial, cache));
		}
		assertFalse(Files.exists(layout.objectFile(sha1)));
		assertEquals(List.of(), stagingLeftovers(layout));
	}

	@Test
	void anObjectAlreadyInTheStoreIsDedupedNotAnError(@TempDir Path root) throws Exception {
		DataRootResolver.Layout layout = new DataRootResolver.Layout(root);
		ObjectPromotion promotion = new ObjectPromotion(layout);
		for (int size : new int[]{64_000, NetUtils.WIRE_CHUNK_BYTES + 5}) {
			byte[] data = bytes(size, 20 + size);
			String sha1 = HashUtils.sha1(data);
			Path object = layout.objectFile(sha1);
			Files.createDirectories(object.getParent());
			Files.write(object, data);
			ImmutableFiles.protect(object);
			QueuedDownload task = task(sha1, size);
			Path partial = stage(promotion, task, data);
			try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
				assertTrue(promotion.promote(sha1, task, partial, cache), "another download won the race for size " + size);
				assertPublished(layout, sha1, data, cache);
			}
		}
	}

	@Test
	void aCorruptObjectInTheStoreIsReplacedByTheVerifiedDownload(@TempDir Path root) throws Exception {
		DataRootResolver.Layout layout = new DataRootResolver.Layout(root);
		ObjectPromotion promotion = new ObjectPromotion(layout);
		byte[] data = bytes(70_000, 5);
		String sha1 = HashUtils.sha1(data);
		Path object = layout.objectFile(sha1);
		Files.createDirectories(object.getParent());
		Files.write(object, new byte[data.length]);
		QueuedDownload task = task(sha1, data.length);
		Path partial = stage(promotion, task, data);
		try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
			assertTrue(promotion.promote(sha1, task, partial, cache));
			assertPublished(layout, sha1, data, cache);
		}
	}

	@Test
	void anEmptyObjectIsPromoted(@TempDir Path root) throws Exception {
		DataRootResolver.Layout layout = new DataRootResolver.Layout(root);
		ObjectPromotion promotion = new ObjectPromotion(layout);
		String sha1 = HashUtils.sha1(new byte[0]);
		QueuedDownload task = task(sha1, 0);
		Path partial = promotion.preparePartial(sha1, task);
		try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
			assertTrue(promotion.promote(sha1, task, partial, cache));
			assertPublished(layout, sha1, new byte[0], cache);
		}
	}
}
