package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

class SizeAwareForceTest {
	static ObjectBatch.Held held(DataRootResolver.Layout layout, int id, int size) throws Exception {
		byte[] bytes = new byte[size];
		new Random(id).nextBytes(bytes);
		String hash = HashUtils.sha1(bytes);
		Path directory = Files.createDirectories(layout.stagingDirectory().resolve(hash));
		Path file = Files.write(directory.resolve(".held.sized.obj"), bytes);
		var task = new QueuedDownload(hash, id + ".jar", List.of(), null, "mods", size, id, () -> {}, ignored -> {});
		return new ObjectBatch.Held(hash, size, file, directory, task);
	}

	/** An object whose size the pack never advertised; it can only be forced and published at join. */
	static ObjectBatch.Held unknownSize(DataRootResolver.Layout layout, int id) throws Exception {
		byte[] bytes = ("unknown size verified bytes " + id).getBytes(StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(bytes);
		Path directory = Files.createDirectories(layout.stagingDirectory().resolve(hash));
		Path file = Files.write(directory.resolve(".held.unknown.obj"), bytes);
		var task = new QueuedDownload(hash, id + ".jar", List.of(), null, "mods", -1, id, () -> {}, ignored -> {});
		return new ObjectBatch.Held(hash, -1, file, directory, task);
	}

	@Test
	void largeAndUnknownSizeObjectsTakeTheJoinTimePath(@TempDir Path root) throws Exception {
		var layout = new DataRootResolver.Layout(root);
		var large = held(layout, 1, (1 << 20) + 1);
		var unknown = unknownSize(layout, 2);
		var small = SingleDaemonForceTest.held(layout, 3);
		Map<Path, Thread> forcing = new ConcurrentHashMap<>();
		Set<Path> forced = ConcurrentHashMap.newKeySet();
		CountDownLatch smallForced = new CountDownLatch(1);
		ObjectBatch batch = ObjectBatch.asynchronous(layout, (step, path) -> {
			if (step == ObjectBatch.Step.BEFORE_FORCE) forcing.put(path, Thread.currentThread());
			if (step == ObjectBatch.Step.FORCED) {
				forced.add(path);
				if (path.equals(small.file())) smallForced.countDown();
			}
			if (step == ObjectBatch.Step.BEFORE_PUBLISH) assertTrue(forced.contains(path), "publication before content force");
		});
		try {
			batch.hold(large);
			batch.hold(unknown);
			batch.hold(small);
			assertTrue(smallForced.await(30, TimeUnit.SECONDS), "the small object is forced while the download run continues");
			assertFalse(forcing.containsKey(large.file()), "a large object never reaches the pump");
			assertFalse(forcing.containsKey(unknown.file()), "an unknown-size object never reaches the pump");
			assertTrue(batch.flush().isEmpty());
			assertEquals(Thread.currentThread(), forcing.get(large.file()), "a large object is forced by the joining caller");
			assertEquals(Thread.currentThread(), forcing.get(unknown.file()), "an unknown-size object is forced by the joining caller");
			assertTrue(forcing.get(small.file()).isDaemon());
			assertNotEquals(Thread.currentThread(), forcing.get(small.file()));
			for (var object : List.of(large, unknown, small)) assertEquals(object.sha1(), HashUtils.hashFile(layout.objectFile(object.sha1())));
		} finally {
			batch.close();
		}
	}

	@Test
	void removedDeferredLargeBytesAreReportedOnceWithoutARecord(@TempDir Path root) throws Exception {
		var layout = new DataRootResolver.Layout(root);
		var large = held(layout, 3, (1 << 20) + 1);
		var small = held(layout, 4, 32);
		CountDownLatch smallForced = new CountDownLatch(1);
		AtomicInteger largeAttempts = new AtomicInteger();
		ObjectBatch batch = ObjectBatch.asynchronous(layout, (step, path) -> {
			if (step == ObjectBatch.Step.FORCED && path.equals(small.file())) smallForced.countDown();
			if (step == ObjectBatch.Step.BEFORE_FORCE && path.equals(large.file())) {
				largeAttempts.incrementAndGet();
				try {
					Files.deleteIfExists(path);
				} catch (IOException failure) {
					throw new AssertionError(failure);
				}
			}
		});
		try {
			batch.hold(large);
			batch.hold(small);
			assertTrue(smallForced.await(30, TimeUnit.SECONDS));
			assertEquals(0, largeAttempts.get());
			assertEquals(List.of(large.task()), batch.flush());
			assertEquals(1, largeAttempts.get());
			assertEquals(DownloadManager.FailureCategory.UNKNOWN, large.task().lastFailureCategory);
			assertFalse(Files.exists(layout.objectFile(large.sha1())));
			assertEquals(small.sha1(), HashUtils.hashFile(layout.objectFile(small.sha1())));
			try (var cache = FileCache.open(layout.fileCacheDirectory())) {
				assertNull(cache.getHashOrNull(layout.objectFile(large.sha1())));
			}
			assertTrue(batch.flush().isEmpty());
			assertEquals(1, largeAttempts.get());
		} finally {
			batch.close();
		}
	}
}
