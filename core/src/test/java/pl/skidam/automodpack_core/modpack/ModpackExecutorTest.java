package pl.skidam.automodpack_core.modpack;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.modpack.candidate.CandidateBuildException;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidateScanner;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.modpack.generation.GenerationStore;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.storage.StoragePaths;

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
		Path notes = groups.resolve("patch-notes.md");

		ConstantsSnapshot snapshot = new ConstantsSnapshot();
		Constants.serverConfig = config();
		Constants.AM_VERSION = "test";
		Constants.LOADER = "test";
		Constants.LOADER_VERSION = "test";
		Constants.MC_VERSION = "test";
		String previous = System.setProperty(StoragePaths.DATA_ROOT_PROPERTY, tempDir.resolve("data").toAbsolutePath().normalize().toString());
		ModpackExecutor executor = new ModpackExecutor(server, groups, generationRoot);
		try {
			assertEquals("A state guard is unavailable before the root generation is published",
					assertInstanceOf(ModpackExecutor.PublishResult.Rejected.class, executor.publishIfContent("0".repeat(40))).detail());
			ModpackExecutor.PreviewResult preview = executor.preview();
			ModpackExecutor.PreviewReady ready = assertInstanceOf(ModpackExecutor.PreviewReady.class, preview);
			assertTrue(Files.notExists(generationRoot.resolve(StoragePaths.SERVER_JOURNAL_FILE.getFileName().toString())));
			assertTrue(Files.notExists(generationRoot.resolve(StoragePaths.SERVER_PROJECTION_FILE.getFileName().toString())));
			assertTrue(Files.notExists(generationRoot.resolve(StoragePaths.SERVER_STAGING_DIR.getFileName().toString())));
			assertTrue(executor.currentDocument().isEmpty());
			assertTrue(Files.exists(notes));
			assertEquals("", Files.readString(notes, StandardCharsets.UTF_8));

			ModpackExecutor.PublishResult root = executor.publish();
			ModpackExecutor.Published publishedRoot = assertInstanceOf(ModpackExecutor.Published.class, root);
			assertTrue(publishedRoot.state().parent().isEmpty());
			String rootToken = publishedRoot.current().contentToken();
			assertEquals(rootToken, ready.state().contentToken());
			var rootDocument = executor.currentDocument().orElseThrow();
			assertEquals(rootDocument, assertInstanceOf(ModpackExecutor.NoChanges.class, executor.publish()).current());
			assertTrue(Files.exists(generationRoot.resolve(StoragePaths.SERVER_JOURNAL_FILE.getFileName().toString())));
			assertTrue(Files.exists(generationRoot.resolve(StoragePaths.SERVER_PROJECTION_FILE.getFileName().toString())));

			Files.writeString(notes, "pending", StandardCharsets.UTF_8);
			assertInstanceOf(ModpackExecutor.NoChanges.class, executor.publish());
			assertTrue(Files.exists(notes));

			Files.writeString(source, "two", StandardCharsets.UTF_8);
			ModpackExecutor.PublishResult.Rejected mismatch = assertInstanceOf(ModpackExecutor.PublishResult.Rejected.class, executor.publishIfContent(rootToken));
			assertEquals("Fresh candidate content does not match the requested guard", mismatch.detail());
			assertNull(mismatch.cause());
			assertTrue(Files.exists(notes));

			String nextToken = assertInstanceOf(ModpackExecutor.PreviewReady.class, executor.preview()).state().contentToken();
			ModpackExecutor.Published changed = assertInstanceOf(ModpackExecutor.Published.class, executor.publishIfContent(nextToken));
			assertEquals(nextToken, changed.state().contentToken());
			assertEquals(rootToken, changed.state().parent().orElseThrow().contentToken());
			assertEquals(nextToken, executor.currentDocument().orElseThrow().contentToken());
			assertTrue(Files.exists(notes));
			assertEquals("", Files.readString(notes, StandardCharsets.UTF_8));

			ModpackExecutor.Reverted reverted = assertInstanceOf(ModpackExecutor.Reverted.class, executor.revert(1, "back to first"));
			assertEquals(rootToken, reverted.current().contentToken());
			assertEquals(1, reverted.targetSeq());
			assertEquals(3, executor.technicalHistory(10).size());
			assertEquals(rootToken, executor.currentDocument().orElseThrow().contentToken());
			assertEquals(3, executor.storageReport().journalEntries());
		} finally {
			executor.stop();
			snapshot.restore();
			if (previous == null) System.clearProperty(StoragePaths.DATA_ROOT_PROPERTY);
			else System.setProperty(StoragePaths.DATA_ROOT_PROPERTY, previous);
		}
	}

	@Test
	void previewAndPublishAdmissionDoesNotQueue() throws Exception {
		Path groups = tempDir.resolve("host-modpack");
		Files.createDirectories(groups.resolve("main/config"));
		Files.writeString(groups.resolve("main/config/example.txt"), "content", StandardCharsets.UTF_8);
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
				new GenerationStore(tempDir.resolve("host-generations"), tempDir.resolve("objects")), scan, creation);
		var operationExecutor = Executors.newSingleThreadExecutor();
		try {
			Future<ModpackExecutor.PublishResult> first = operationExecutor.submit(() -> executor.publish());
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			assertEquals("Another modpack operation is already in progress",
					assertInstanceOf(ModpackExecutor.PreviewResult.Rejected.class, executor.preview()).detail());
			release.countDown();
			assertInstanceOf(ModpackExecutor.Published.class, first.get());
		} finally {
			release.countDown();
			operationExecutor.shutdownNow();
			executor.stop();
			snapshot.restore();
		}
	}

	@Test
	void repeatedPreviewsReuseInjectedDataLayoutWithoutResolvingServerRoot() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("host-modpack");
		Path generationRoot = tempDir.resolve("host-generations");
		Path dataRoot = tempDir.resolve("selected-data");
		Files.createDirectories(groups.resolve("main/config"));
		Files.writeString(groups.resolve("main/config/example.txt"), "content", StandardCharsets.UTF_8);

		ConstantsSnapshot snapshot = new ConstantsSnapshot();
		Constants.serverConfig = config();
		Constants.AM_VERSION = "test";
		Constants.LOADER = "test";
		Constants.LOADER_VERSION = "test";
		Constants.MC_VERSION = "test";
		ThreadPoolExecutor creation = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
		ModpackExecutor executor = new ModpackExecutor(server, groups, generationRoot, new GenerationStore(generationRoot, dataRoot.resolve("objects")), new ModpackCandidateScanner()::scan,
				creation);
		try {
			assertInstanceOf(ModpackExecutor.PreviewReady.class, executor.preview());
			assertInstanceOf(ModpackExecutor.PreviewReady.class, executor.preview());
			assertTrue(Files.isDirectory(dataRoot.resolve("file-cache")));
			assertTrue(Files.isDirectory(dataRoot.resolve("mod-cache")));
		} finally {
			executor.stop();
			snapshot.restore();
		}
	}

	@Test
	void restartWithoutChangesKeepsModpackHostingPrepared() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("host-modpack");
		Path generationRoot = tempDir.resolve("host-generations");
		Path source = groups.resolve("main/config/example.txt");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "one", StandardCharsets.UTF_8);

		ConstantsSnapshot snapshot = new ConstantsSnapshot();
		Constants.serverConfig = config();
		Constants.AM_VERSION = "test";
		Constants.LOADER = "test";
		Constants.LOADER_VERSION = "test";
		Constants.MC_VERSION = "test";
		String previous = System.setProperty(StoragePaths.DATA_ROOT_PROPERTY, tempDir.resolve("data").toAbsolutePath().normalize().toString());
		ModpackExecutor first = new ModpackExecutor(server, groups, generationRoot);
		try {
			Constants.hostServer = new NettyServer();
			assertInstanceOf(ModpackExecutor.Published.class, first.publish());
			assertTrue(Constants.hostServer.getPath(GenerationHosting.HEAD_DOCUMENT_KEY).isPresent());

			// A server restart builds a fresh host and executor over the same on-disk journal.
			Constants.hostServer = new NettyServer();
			ModpackExecutor restarted = new ModpackExecutor(server, groups, generationRoot);
			try {
				assertInstanceOf(ModpackExecutor.NoChanges.class, restarted.publish());
				assertTrue(Constants.hostServer.getPath(GenerationHosting.HEAD_DOCUMENT_KEY).isPresent());
			} finally {
				restarted.stop();
			}
		} finally {
			first.stop();
			snapshot.restore();
			if (previous == null) System.clearProperty(StoragePaths.DATA_ROOT_PROPERTY);
			else System.setProperty(StoragePaths.DATA_ROOT_PROPERTY, previous);
		}
	}

	@Test
	void hostingSwapFailureAfterCommitIsReportedOnTheCommittedOutcome() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("host-modpack");
		Path generationRoot = tempDir.resolve("host-generations");
		Path source = groups.resolve("main/config/example.txt");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "content", StandardCharsets.UTF_8);

		ConstantsSnapshot snapshot = new ConstantsSnapshot();
		Constants.serverConfig = config();
		Constants.AM_VERSION = "test";
		Constants.LOADER = "test";
		Constants.LOADER_VERSION = "test";
		Constants.MC_VERSION = "test";
		ThreadPoolExecutor creation = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
		ModpackExecutor executor = new ModpackExecutor(server, groups, generationRoot, new GenerationStore(generationRoot, tempDir.resolve("objects")),
				new ModpackCandidateScanner()::scan, creation, hosting -> {
					throw new IllegalStateException("swap boom");
				});
		try {
			ModpackExecutor.Published published = assertInstanceOf(ModpackExecutor.Published.class, executor.publish());
			assertEquals("swap boom", published.hostingFailure().orElseThrow().getMessage());
			// The commit is durable even though the host was never rebinded.
			assertEquals(published.current().contentToken(), executor.currentDocument().orElseThrow().contentToken());

			Files.writeString(source, "changed", StandardCharsets.UTF_8);
			ModpackExecutor.Published changed = assertInstanceOf(ModpackExecutor.Published.class, executor.publish());
			assertEquals("swap boom", changed.hostingFailure().orElseThrow().getMessage());
			assertEquals("swap boom", assertInstanceOf(ModpackExecutor.NoChanges.class, executor.publish()).hostingFailure().orElseThrow().getMessage());

			ModpackExecutor.Reverted reverted = assertInstanceOf(ModpackExecutor.Reverted.class, executor.revert(1, null));
			assertEquals("swap boom", reverted.hostingFailure().orElseThrow().getMessage());
		} finally {
			executor.stop();
			snapshot.restore();
		}
	}

	@Test
	void cleanHostingSwapAndGuardFailuresKeepTheirPlainShapes() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("host-modpack");
		Path generationRoot = tempDir.resolve("host-generations");
		Path source = groups.resolve("main/config/example.txt");
		Files.createDirectories(source.getParent());
		Files.writeString(source, "content", StandardCharsets.UTF_8);

		ConstantsSnapshot snapshot = new ConstantsSnapshot();
		Constants.serverConfig = config();
		Constants.AM_VERSION = "test";
		Constants.LOADER = "test";
		Constants.LOADER_VERSION = "test";
		Constants.MC_VERSION = "test";
		ThreadPoolExecutor creation = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
		List<GenerationHosting> bound = new ArrayList<>();
		ModpackExecutor clean = new ModpackExecutor(server, groups, generationRoot, new GenerationStore(generationRoot, tempDir.resolve("objects-clean")),
				new ModpackCandidateScanner()::scan, creation, bound::add);
		ModpackExecutor guarded = new ModpackExecutor(tempDir.resolve("server-guarded"), tempDir.resolve("guarded-groups"), tempDir.resolve("guarded-generations"),
				new GenerationStore(tempDir.resolve("guarded-generations"), tempDir.resolve("guarded-objects")),
				request -> {
					throw new CandidateBuildException("Candidate scan failed");
				}, creation);
		try {
			ModpackExecutor.Published published = assertInstanceOf(ModpackExecutor.Published.class, clean.publish());
			assertTrue(published.hostingFailure().isEmpty());
			assertEquals(1, bound.size());

			assertEquals("Guard token must be a canonical 40-character lowercase SHA-1",
					assertInstanceOf(ModpackExecutor.PublishResult.Rejected.class, clean.publishIfContent("nope")).detail());

			ModpackExecutor.PublishResult.Rejected scanFailure = assertInstanceOf(ModpackExecutor.PublishResult.Rejected.class, guarded.publish());
			assertEquals("Candidate scan failed", scanFailure.detail());
		} finally {
			clean.stop();
			guarded.stop();
			snapshot.restore();
		}
	}

	private static ServerConfigJsons.ServerConfigFieldsV3 config() {
		ServerConfigJsons.ServerConfigFieldsV3 config = new ServerConfigJsons.ServerConfigFieldsV3();
		ServerConfigJsons.GroupDeclaration main = new ServerConfigJsons.GroupDeclaration();
		main.required = true;
		main.syncedFiles = Set.of();
		config.modpack = Map.of("General", Map.of("main", main));
		config.autoExcludeUnnecessaryFiles = false;
		config.autoExcludeServerSideMods = false;
		return config;
	}

	private static final class ConstantsSnapshot {
		private final ServerConfigJsons.ServerConfigFieldsV3 serverConfig = Constants.serverConfig;
		private final String amVersion = Constants.AM_VERSION;
		private final String loader = Constants.LOADER;
		private final String loaderVersion = Constants.LOADER_VERSION;
		private final String mcVersion = Constants.MC_VERSION;
		private final NettyServer hostServer = Constants.hostServer;

		void restore() {
			Constants.serverConfig = serverConfig;
			Constants.AM_VERSION = amVersion;
			Constants.LOADER = loader;
			Constants.LOADER_VERSION = loaderVersion;
			Constants.MC_VERSION = mcVersion;
			Constants.hostServer = hostServer;
		}
	}
}
