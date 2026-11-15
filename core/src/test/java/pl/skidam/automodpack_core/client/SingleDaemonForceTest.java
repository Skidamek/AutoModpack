package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.cache.FileCache;

class SingleDaemonForceTest {
	static ObjectBatch.Held held(DataRootResolver.Layout layout, int id) throws Exception {
		byte[] bytes = new byte[64];
		new Random(id).nextBytes(bytes);
		String hash = HashUtils.sha1(bytes);
		Path directory = Files.createDirectories(layout.stagingDirectory().resolve(hash));
		Path file = Files.write(directory.resolve(".held.test.obj"), bytes);
		var task = new QueuedDownload(hash, id + ".jar", List.of(), null, "mods", bytes.length, id, () -> {}, ignored -> {});
		return new ObjectBatch.Held(hash, bytes.length, file, directory, task);
	}

	@Test
	void noObjectIsPublishedBeforeItsOwnForce(@TempDir Path root) throws Exception {
		int producers = 8, each = 16;
		for (int round = 0; round < 4; round++) {
			var layout = new DataRootResolver.Layout(root.resolve("round" + round));
			Set<Path> forced = ConcurrentHashMap.newKeySet();
			Map<Path, AtomicInteger> forces = new ConcurrentHashMap<>();
			List<String> violations = new CopyOnWriteArrayList<>();
			ObjectBatch batch = ObjectBatch.asynchronous(layout, (step, path) -> {
				if (step == ObjectBatch.Step.FORCED) {
					forced.add(path);
					forces.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
				}
				if (step == ObjectBatch.Step.BEFORE_PUBLISH && !forced.contains(path)) violations.add(path.toString());
			});
			List<List<ObjectBatch.Held>> objects = new ArrayList<>();
			for (int producer = 0; producer < producers; producer++) {
				List<ObjectBatch.Held> mine = new ArrayList<>();
				for (int index = 0; index < each; index++) mine.add(held(layout, round * 10_000 + producer * 100 + index));
				objects.add(mine);
			}
			CyclicBarrier start = new CyclicBarrier(producers + 1);
			ExecutorService pool = Executors.newFixedThreadPool(producers);
			try {
				List<Future<?>> holds = new ArrayList<>();
				for (List<ObjectBatch.Held> mine : objects) holds.add(pool.submit(() -> {
					start.await();
					for (ObjectBatch.Held object : mine) batch.hold(object);
					return null;
				}));
				start.await();
				// The flush runs while producers are still holding: admission and the pump race for the same objects.
				List<QueuedDownload> failed = new ArrayList<>(batch.flush());
				for (Future<?> hold : holds) hold.get(30, TimeUnit.SECONDS);
				failed.addAll(batch.flush());
				assertEquals(List.of(), failed);
				assertEquals(List.of(), violations);
				for (List<ObjectBatch.Held> mine : objects) for (ObjectBatch.Held object : mine) {
					assertEquals(1, forces.get(object.file()).get(), "each object is forced exactly once");
					assertEquals(object.sha1(), HashUtils.hashFile(layout.objectFile(object.sha1())));
				}
			} finally {
				pool.shutdownNow();
				batch.close();
			}
		}
	}

	static class Crash extends Error {}

	@Test
	void everyCrashBoundaryKeepsForcePublishDirectoryRecordOrder(@TempDir Path root) throws Exception {
		for (var stop : ObjectBatch.Step.values()) {
			var layout = new DataRootResolver.Layout(root.resolve(stop.name()));
			Set<Path> forced = ConcurrentHashMap.newKeySet(), directories = ConcurrentHashMap.newKeySet();
			ObjectBatch batch = ObjectBatch.asynchronous(layout, (step, path) -> {
				if (step == ObjectBatch.Step.FORCED) forced.add(path);
				if (step == ObjectBatch.Step.BEFORE_PUBLISH) assertTrue(forced.contains(path), "publication before content force");
				if (step == ObjectBatch.Step.DIRECTORY_FORCED) directories.add(path);
				if (step == ObjectBatch.Step.BEFORE_RECORD) assertTrue(directories.contains(path.getParent()), "record before namespace force");
				if (step == stop) throw new Crash();
			});
			List<ObjectBatch.Held> all = new ArrayList<>();
			try {
				for (int i = 0; i < 8; i++) {
					var next = held(layout, i);
					all.add(next);
					batch.hold(next);
				}
				assertThrows(Crash.class, batch::flush);
				for (var next : all) if (Files.exists(layout.objectFile(next.sha1()))) assertEquals(next.sha1(), HashUtils.hashFile(layout.objectFile(next.sha1())));
			} finally {
				batch.close();
			}
		}
	}

	@Test
	void anEioForceFailsOnlyItsOwnObjectAndNeverPublishesIt(@TempDir Path root) throws Exception {
		var layout = new DataRootResolver.Layout(root);
		var bad = held(layout, 0);
		// The seam stands in for a store that reports EIO on this path's fsync, without a real media fault.
		ObjectBatch batch = ObjectBatch.asynchronous(layout, (step, path) -> {}, file -> {
			if (file.equals(bad.file())) throw new IOException("Input/output error");
			ImmutableFiles.protectAndForce(file);
		});
		List<ObjectBatch.Held> good = new ArrayList<>();
		try {
			batch.hold(bad);
			for (int i = 1; i < 8; i++) {
				var next = held(layout, i);
				good.add(next);
				batch.hold(next);
			}
			assertEquals(List.of(bad.task()), batch.flush());
			assertEquals(DownloadManager.FailureCategory.LOCAL_STORAGE, bad.task().lastFailureCategory);
			assertFalse(Files.exists(layout.objectFile(bad.sha1())), "an object whose force failed never publishes");
			assertFalse(Files.exists(bad.file()), "held bytes of a failed force are discarded");
			for (var next : good) assertEquals(next.sha1(), HashUtils.hashFile(layout.objectFile(next.sha1())));
		} finally {
			batch.close();
		}
	}

	@Test
	void removedHeldBytesFailOnlyTheirTaskAndNeverRecordIt(@TempDir Path root) throws Exception {
		var layout = new DataRootResolver.Layout(root);
		var bad = held(layout, 0);
		ObjectBatch batch = ObjectBatch.asynchronous(layout, (step, path) -> {
			if (step == ObjectBatch.Step.BEFORE_FORCE && path.equals(bad.file())) try {
				Files.deleteIfExists(path);
			} catch (IOException failure) {
				throw new AssertionError(failure);
			}
		});
		List<ObjectBatch.Held> good = new ArrayList<>();
		try {
			batch.hold(bad);
			for (int i = 1; i < 8; i++) {
				var next = held(layout, i);
				good.add(next);
				batch.hold(next);
			}
			assertEquals(List.of(bad.task()), batch.flush());
			assertEquals(DownloadManager.FailureCategory.UNKNOWN, bad.task().lastFailureCategory);
			assertFalse(Files.exists(layout.objectFile(bad.sha1())));
			for (var next : good) assertEquals(next.sha1(), HashUtils.hashFile(layout.objectFile(next.sha1())));
			try (var cache = FileCache.open(layout.fileCacheDirectory())) {
				assertNull(cache.getHashOrNull(layout.objectFile(bad.sha1())));
			}
		} finally {
			batch.close();
		}
	}
}
