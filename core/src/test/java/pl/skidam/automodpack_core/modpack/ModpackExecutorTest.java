package pl.skidam.automodpack_core.modpack;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.candidate.CandidateBuildException;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidateScanner;
import pl.skidam.automodpack_core.modpack.generation.GenerationStore;

class ModpackExecutorTest {
	@TempDir
	Path tempDir;

	@Test
	void previewGuardAndPatchNotePublicationPreserveCommitBoundaries() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("host-modpack");
		Path generationRoot = tempDir.resolve("host-generations");
		Path source = groups.resolve("main/config/example.txt");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "one", StandardCharsets.UTF_8);
		Path notes = tempDir.resolve("host-patch-notes.md");

		ConstantsSnapshot snapshot = new ConstantsSnapshot();
		Constants.serverConfig = config();
		Constants.AM_VERSION = "test";
		Constants.LOADER = "test";
		Constants.LOADER_VERSION = "test";
		Constants.MC_VERSION = "test";
		ModpackExecutor executor = new ModpackExecutor(server, groups, generationRoot);
		try {
			assertInstanceOf(ModpackExecutor.PublishGuardUnsupported.class, executor.publishIfState("0".repeat(40)));
			ModpackExecutor.PreviewResult preview = executor.preview();
			ModpackExecutor.PreviewReady ready = assertInstanceOf(ModpackExecutor.PreviewReady.class, preview);
			assertTrue(Files.notExists(generationRoot.resolve("current.json")));
			assertTrue(Files.notExists(generationRoot.resolve("records")));
			assertTrue(Files.notExists(generationRoot.resolve("objects")));
			assertTrue(Files.isDirectory(generationRoot.resolve("staging")));
			try (var entries = Files.list(generationRoot.resolve("staging"))) {
				assertEquals(0, entries.count());
			}

			ModpackExecutor.PublishResult root = executor.publish();
			ModpackExecutor.Published publishedRoot = assertInstanceOf(ModpackExecutor.Published.class, root);
			String rootDigest = publishedRoot.state().candidateStateDigest();
			Files.writeString(notes, "pending", StandardCharsets.UTF_8);
			assertInstanceOf(ModpackExecutor.NoChanges.class, executor.publish());
			assertTrue(Files.exists(notes));

			Files.writeString(source, "two", StandardCharsets.UTF_8);
			assertInstanceOf(ModpackExecutor.PublishGuardMismatch.class, executor.publishIfState(rootDigest));
			assertTrue(Files.exists(notes));

			String nextDigest = assertInstanceOf(ModpackExecutor.PreviewReady.class, executor.preview()).state().candidateStateDigest();
			ModpackExecutor.Published changed = assertInstanceOf(ModpackExecutor.Published.class, executor.publishIfState(nextDigest));
			assertEquals(nextDigest, changed.state().candidateStateDigest());
			assertTrue(Files.notExists(notes));
		} finally {
			executor.stop();
			snapshot.restore();
		}
	}

	@Test
	void deletionDirectivePreviewDigestCanGuardPublication() throws Exception {
		Path groups = tempDir.resolve("host-modpack");
		Files.createDirectories(groups.resolve("main"));
		ConstantsSnapshot snapshot = new ConstantsSnapshot();
		Constants.serverConfig = config();
		Constants.AM_VERSION = "test";
		Constants.LOADER = "test";
		Constants.LOADER_VERSION = "test";
		Constants.MC_VERSION = "test";
		ModpackExecutor executor = new ModpackExecutor(tempDir.resolve("server"), groups, tempDir.resolve("host-generations"));
		try {
			assertInstanceOf(ModpackExecutor.Published.class, executor.publish());
			Constants.serverConfig.nonModpackFilesToDelete = Map.of("/config/deletion.txt", "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8");
			String digest = assertInstanceOf(ModpackExecutor.PreviewReady.class, executor.preview()).state().candidateStateDigest();
			ModpackExecutor.Published published = assertInstanceOf(ModpackExecutor.Published.class, executor.publishIfState(digest));
			assertEquals(digest, published.state().candidateStateDigest());
		} finally {
			executor.stop();
			snapshot.restore();
		}
	}

	@Test
	void previewAndPublishAdmissionDoesNotQueue() throws Exception {
		Path groups = tempDir.resolve("host-modpack");
		Files.createDirectories(groups.resolve("main"));
		ConstantsSnapshot snapshot = new ConstantsSnapshot();
		Constants.serverConfig = config();
		Constants.AM_VERSION = "test";
		Constants.LOADER = "test";
		Constants.LOADER_VERSION = "test";
		Constants.MC_VERSION = "test";
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ModpackCandidateScanner scanner = new ModpackCandidateScanner();
		ModpackExecutor.CandidateScan scan = request -> {
			entered.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new CandidateBuildException("Interrupted", e);
			}
			return scanner.scan(request);
		};
		ThreadPoolExecutor creation = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
		ModpackExecutor executor = new ModpackExecutor(tempDir.resolve("server"), groups, tempDir.resolve("host-generations"),
				new GenerationStore(tempDir.resolve("host-generations")), scan, creation);
		var operationExecutor = Executors.newSingleThreadExecutor();
		try {
			Future<ModpackExecutor.PublishResult> first = operationExecutor.submit(() -> executor.publish());
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			assertInstanceOf(ModpackExecutor.PreviewBusy.class, executor.preview());
			release.countDown();
			assertInstanceOf(ModpackExecutor.Published.class, first.get());
		} finally {
			release.countDown();
			operationExecutor.shutdownNow();
			executor.stop();
			snapshot.restore();
		}
	}

	private static Jsons.ServerConfigFieldsV3 config() {
		Jsons.ServerConfigFieldsV3 config = new Jsons.ServerConfigFieldsV3();
		Jsons.GroupDeclaration main = new Jsons.GroupDeclaration();
		main.required = true;
		main.syncedFiles = Set.of();
		config.groups = Map.of("main", main);
		config.selectionTags = Map.of();
		config.nonModpackFilesToDelete = Map.of();
		config.autoExcludeUnnecessaryFiles = false;
		config.autoExcludeServerSideMods = false;
		return config;
	}

	private static final class ConstantsSnapshot {
		private final Jsons.ServerConfigFieldsV3 serverConfig = Constants.serverConfig;
		private final String amVersion = Constants.AM_VERSION;
		private final String loader = Constants.LOADER;
		private final String loaderVersion = Constants.LOADER_VERSION;
		private final String mcVersion = Constants.MC_VERSION;

		void restore() {
			Constants.serverConfig = serverConfig;
			Constants.AM_VERSION = amVersion;
			Constants.LOADER = loader;
			Constants.LOADER_VERSION = loaderVersion;
			Constants.MC_VERSION = mcVersion;
		}
	}
}
