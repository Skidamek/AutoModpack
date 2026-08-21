package pl.skidam.automodpack_loader_core.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.HashUtils;

class DownloadManagerTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void cacheHitIsRemovedBeforeTheNextDownloadIsScheduled() throws Exception {
		ClientStorage storage = ClientStorage.open(temporaryDirectory.resolve("game"));
		byte[] bytes = "already-cached".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(bytes);
		Files.write(storage.objectsDirectory().resolve(hash), bytes);
		ExecutorService directExecutor = new DirectExecutorService();
		DownloadManager manager = new DownloadManager(bytes.length, storage, directExecutor);

		try {
			manager.download(temporaryDirectory.resolve("game/mods/cached.jar"), hash, List.of(), bytes.length,
					() -> {}, failure -> { throw new AssertionError("cache hit failed: " + failure); });

			assertTrue(manager.downloadsInProgress.isEmpty());
		} finally {
			manager.cancelAllAndShutdown();
		}
	}

	@Test
	@Timeout(2)
	void callbackFailureStillCompletesTheDownloadLifecycle() throws Exception {
		ClientStorage storage = ClientStorage.open(temporaryDirectory.resolve("game"));
		byte[] bytes = "callback-failure".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		String hash = HashUtils.sha1(bytes);
		Files.write(storage.objectsDirectory().resolve(hash), bytes);
		ExecutorService directExecutor = new DirectExecutorService();
		DownloadManager manager = new DownloadManager(bytes.length, storage, directExecutor);

		try {
			manager.download(temporaryDirectory.resolve("game/mods/callback.jar"), hash, List.of(), bytes.length,
					() -> { throw new IllegalStateException("callback failed"); }, failure -> { throw new AssertionError(failure); });

			manager.joinAll();
		} finally {
			manager.cancelAllAndShutdown();
		}
	}

	private static final class DirectExecutorService extends AbstractExecutorService {
		private boolean shutdown;

		@Override
		public void shutdown() {
			shutdown = true;
		}

		@Override
		public java.util.List<Runnable> shutdownNow() {
			shutdown = true;
			return List.of();
		}

		@Override
		public boolean isShutdown() {
			return shutdown;
		}

		@Override
		public boolean isTerminated() {
			return shutdown;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return shutdown;
		}

		@Override
		public void execute(Runnable command) {
			if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
			command.run();
		}
	}
}
