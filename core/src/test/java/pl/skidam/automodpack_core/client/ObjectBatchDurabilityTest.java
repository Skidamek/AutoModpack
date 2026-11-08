package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.cache.FileCache;

class ObjectBatchDurabilityTest {
	private static final class Crash extends Error {}

	private static final List<byte[]> CONTENTS = List.of("one verified object".getBytes(StandardCharsets.UTF_8), "another verified object".getBytes(StandardCharsets.UTF_8));

	private static QueuedDownload task(byte[] bytes) {
		String hash = HashUtils.sha1(bytes);
		return new QueuedDownload(hash, "mod.jar", List.of(), null, "mods", bytes.length, 0, () -> {}, category -> {});
	}

	private static void stage(ObjectPromotion promotion, byte[] bytes) throws Exception {
		QueuedDownload task = task(bytes);
		Path partial = promotion.preparePartial(task.sha1, task);
		try (OutputStream out = PartialResume.writer(partial, bytes.length, 0)) {
			out.write(bytes);
		}
		assertTrue(promotion.promote(task.sha1, task, partial));
	}

	@Test
	void failedForceOrPublicationNeverSeedsAHashRecord(@TempDir Path root) throws Exception {
		for (ObjectBatch.Step failAt : List.of(ObjectBatch.Step.BEFORE_FORCE, ObjectBatch.Step.BEFORE_PUBLISH)) {
			DataRootResolver.Layout layout = new DataRootResolver.Layout(root.resolve(failAt.name()));
			ObjectBatch batch = new ObjectBatch(layout, (step, path) -> {
				if (step == failAt) {
					try {
						ImmutableFiles.deleteIfExists(path);
					} catch (Exception e) {
						throw new AssertionError(e);
					}
				}
			});
			stage(new ObjectPromotion(layout, layout.stagingDirectory(), batch), CONTENTS.get(0));
			assertEquals(1, batch.flush().size());
			assertFalse(Files.exists(layout.objectFile(HashUtils.sha1(CONTENTS.get(0)))));
			if (Files.exists(layout.fileCacheDirectory())) {
				try (var files = Files.walk(layout.fileCacheDirectory())) {
					assertTrue(files.noneMatch(file -> file.toString().endsWith(".json")), "failed objects have no trusted cache record");
				}
			}
		}
	}

	@Test
	void crashAtEveryBarrierCanOnlyExposeWholeObjects(@TempDir Path root) throws Exception {
		List<ObjectBatch.Step> trace = new ArrayList<>();
		DataRootResolver.Layout countLayout = new DataRootResolver.Layout(root.resolve("count"));
		ObjectBatch counted = new ObjectBatch(countLayout, (step, path) -> trace.add(step));
		ObjectPromotion countedPromotion = new ObjectPromotion(countLayout, countLayout.stagingDirectory(), counted);
		for (byte[] bytes : CONTENTS) stage(countedPromotion, bytes);
		assertTrue(counted.flush().isEmpty());
		assertFalse(trace.isEmpty());
		for (int crashAt = 0; crashAt < trace.size(); crashAt++) {
			DataRootResolver.Layout layout = new DataRootResolver.Layout(root.resolve("crash-" + crashAt));
			int stop = crashAt;
			int[] position = {0};
			Set<Path> forced = new HashSet<>();
			Set<Path> directories = new HashSet<>();
			ObjectBatch batch = new ObjectBatch(layout, (step, path) -> {
				if (step == ObjectBatch.Step.FORCED) {
					forced.add(path);
				}
				if (step == ObjectBatch.Step.BEFORE_PUBLISH) assertTrue(forced.contains(path), "no namespace publication before its content barrier");
				if (step == ObjectBatch.Step.DIRECTORY_FORCED) directories.add(path);
				if (step == ObjectBatch.Step.BEFORE_RECORD) assertTrue(directories.contains(path.getParent()), "no trusted record before the namespace barrier");
				if (position[0]++ == stop) throw new Crash();
			});
			ObjectPromotion promotion = new ObjectPromotion(layout, layout.stagingDirectory(), batch);
			for (byte[] bytes : CONTENTS) stage(promotion, bytes);
			assertThrows(Crash.class, batch::flush, "crash point " + crashAt);
			for (byte[] bytes : CONTENTS) {
				Path object = layout.objectFile(HashUtils.sha1(bytes));
				if (Files.exists(object)) assertArrayEquals(bytes, Files.readAllBytes(object));
			}
			// A new process may lose unforced held bytes; held names never answer a CAS lookup. Simulate a torn
			// held copy, then retry acquisition. It must be rehashed or discarded, never treated as verified.
			try (var paths = Files.walk(layout.stagingDirectory())) {
				for (Path file : paths.filter(Files::isRegularFile).toList()) {
					ImmutableFiles.unprotect(file);
					Files.write(file, new byte[(int) Files.size(file)]);
				}
			}
			ObjectBatch restarted = new ObjectBatch(layout);
			ObjectPromotion retry = new ObjectPromotion(layout, layout.stagingDirectory(), restarted);
			try (FileCache cache = FileCache.open(layout.fileCacheDirectory())) {
				for (byte[] bytes : CONTENTS) {
					QueuedDownload task = task(bytes);
					assertFalse(retry.adoptHeld(task.sha1, task), "torn held bytes cannot become a trusted object");
					stage(retry, bytes);
				}
				assertTrue(restarted.flush().isEmpty());
				for (byte[] bytes : CONTENTS) assertArrayEquals(bytes, Files.readAllBytes(layout.objectFile(HashUtils.sha1(bytes))));
			}
		}
	}
}
