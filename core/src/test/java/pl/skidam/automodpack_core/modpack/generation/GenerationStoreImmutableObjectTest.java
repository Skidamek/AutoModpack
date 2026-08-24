package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidateScanner;
import pl.skidam.automodpack_core.modpack.candidate.StagedObject;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.HashUtils;

class GenerationStoreImmutableObjectTest {
	@TempDir
	Path tempDir;

	@Test
	void publishedObjectRemainsUnchangedAfterSourceMutation() throws Exception {
		Path source = source("before publication");
		GenerationStore store = new GenerationStore(tempDir.resolve("host-generations"));
		NettyServer server = new NettyServer();
		try (ModpackCandidate candidate = scan()) {
			StagedObject staged = onlyObject(candidate);
			Files.writeString(source, "changed before publication", StandardCharsets.UTF_8);

			GenerationStore.Publication publication = store.publish(candidate, Optional.empty(), "");
			server.replacePaths(publication.hostingPaths());

			Path published = DataRootResolver.objectFile(tempDir.resolve("host-generations/objects"), staged.sha1());
			assertEquals("before publication", Files.readString(published, StandardCharsets.UTF_8));
			assertEquals(published, server.getPath(staged.sha1()).orElseThrow());
			Files.writeString(source, "changed after publication", StandardCharsets.UTF_8);
			assertEquals("before publication", Files.readString(published, StandardCharsets.UTF_8));
		}
	}

	@Test
	void reusesVerifiedExistingObject() throws Exception {
		Path root = tempDir.resolve("host-generations");
		GenerationStore store = new GenerationStore(root);
		try (ModpackCandidate candidate = scan()) {
			StagedObject staged = onlyObject(candidate);
			Path existing = DataRootResolver.objectFile(root.resolve("objects"), staged.sha1());
			Files.createDirectories(existing.getParent());
			Files.copy(staged.stagedPath(), existing);

			GenerationStore.Publication publication = store.publish(candidate, Optional.empty(), "");

			assertFalse(Files.exists(staged.stagedPath(), LinkOption.NOFOLLOW_LINKS));
			assertEquals(existing, publication.hostingPaths().get(staged.sha1()));
			assertEquals("before publication", Files.readString(existing, StandardCharsets.UTF_8));
		}
	}

	@Test
	void rejectsCorruptExistingObjectWithoutOverwritingIt() throws Exception {
		Path root = tempDir.resolve("host-generations");
		GenerationStore store = new GenerationStore(root);
		ModpackCandidate candidate = scan();
		StagedObject staged = onlyObject(candidate);
		Path existing = DataRootResolver.objectFile(root.resolve("objects"), staged.sha1());
		Files.createDirectories(existing.getParent());
		Files.writeString(existing, "corrupt", StandardCharsets.UTF_8);

		IOException failure = assertThrows(IOException.class, () -> store.publish(candidate, Optional.empty(), ""));
		candidate.close();

		assertTrue(failure.getMessage().contains("Refusing to replace corrupt immutable object"));
		assertEquals("corrupt", Files.readString(existing, StandardCharsets.UTF_8));
		assertFalse(Files.exists(staged.stagedPath(), LinkOption.NOFOLLOW_LINKS));
	}

	@Test
	void pointerFailureLeavesPromotedObjectAndCompactStateUnreachable() throws Exception {
		Path root = tempDir.resolve("host-generations");
		GenerationStore store = new GenerationStore(root, Clock.systemUTC(), () -> {
			throw new IOException("pointer failure");
		});
		try (ModpackCandidate candidate = scan()) {
			StagedObject staged = onlyObject(candidate);
			assertThrows(IOException.class, () -> store.publish(candidate, Optional.empty(), ""));

			assertFalse(Files.exists(root.resolve("current.json"), LinkOption.NOFOLLOW_LINKS));
			Path promoted = DataRootResolver.objectFile(root.resolve("objects"), staged.sha1());
			assertTrue(Files.isRegularFile(promoted, LinkOption.NOFOLLOW_LINKS));
			assertEquals(staged.sha1(), HashUtils.getHash(promoted));
			try (var commits = Files.list(root.resolve("commits"))) {
				assertEquals(1, commits.count());
			}
			assertFalse(Files.exists(root.resolve("records")));
			assertTrue(store.loadCurrent().isEmpty());
		}
	}

	@Test
	void candidateCloseDeletesUnpromotedStagedObjects() throws Exception {
		ModpackCandidate candidate = scan();
		StagedObject staged = onlyObject(candidate);
		assertTrue(Files.isRegularFile(staged.stagedPath(), LinkOption.NOFOLLOW_LINKS));

		candidate.close();

		assertFalse(Files.exists(staged.stagedPath(), LinkOption.NOFOLLOW_LINKS));
	}

	private Path source(String content) throws IOException {
		Path source = tempDir.resolve("groups/main/config/example.txt");
		Files.createDirectories(source.getParent());
		Files.createDirectories(tempDir.resolve("server"));
		Files.writeString(source, content, StandardCharsets.UTF_8);
		return source;
	}

	private ModpackCandidate scan() throws Exception {
		if (!Files.exists(tempDir.resolve("groups/main/config/example.txt"))) source("before publication");
		Executor direct = Runnable::run;
		ServerConfigJsons.GroupDeclaration main = new ServerConfigJsons.GroupDeclaration();
		main.syncedFiles = Set.of();
		var request = new ModpackCandidateScanner.Request("abc1234", "Test", "1", "fabric", "1", "1", tempDir.resolve("server"),
				tempDir.resolve("groups"), Map.of("main", main), false, false, tempDir.resolve("host-generations/staging"), direct);
		return new ModpackCandidateScanner().scan(request);
	}

	private static StagedObject onlyObject(ModpackCandidate candidate) {
		assertEquals(1, candidate.objects().size());
		return candidate.objects().firstEntry().getValue();
	}
}
