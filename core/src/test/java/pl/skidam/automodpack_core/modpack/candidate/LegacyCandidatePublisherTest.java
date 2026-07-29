package pl.skidam.automodpack_core.modpack.candidate;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.utils.HashUtils;

class LegacyCandidatePublisherTest {
	@TempDir
	Path tempDir;

	@Test
	void publishedObjectRemainsUnchangedAfterSourceMutation() throws Exception {
		Path source = source("before publication");
		Path objects = tempDir.resolve("host-generations/objects");
		NettyServer server = new NettyServer();
		try (ModpackCandidate candidate = scan()) {
			StagedObject staged = onlyObject(candidate);
			Files.writeString(source, "changed before publication", StandardCharsets.UTF_8);

			new LegacyCandidatePublisher(tempDir.resolve("catalogue.json"), objects, tempDir.resolve("host-generations/staging"), server).publish(candidate);

			Path published = objects.resolve(staged.sha1());
			assertEquals("before publication", Files.readString(published, StandardCharsets.UTF_8));
			assertEquals(published, server.getPath(staged.sha1()).orElseThrow());
			Files.writeString(source, "changed after publication", StandardCharsets.UTF_8);
			assertEquals("before publication", Files.readString(published, StandardCharsets.UTF_8));
		}
	}

	@Test
	void reusesVerifiedExistingObject() throws Exception {
		Path objects = tempDir.resolve("host-generations/objects");
		try (ModpackCandidate candidate = scan()) {
			StagedObject staged = onlyObject(candidate);
			Path existing = objects.resolve(staged.sha1());
			Files.createDirectories(objects);
			Files.copy(staged.stagedPath(), existing);

			NettyServer server = new NettyServer();
			new LegacyCandidatePublisher(tempDir.resolve("catalogue.json"), objects, tempDir.resolve("host-generations/staging"), server).publish(candidate);

			assertFalse(Files.exists(staged.stagedPath(), LinkOption.NOFOLLOW_LINKS));
			assertEquals(existing, server.getPath(staged.sha1()).orElseThrow());
			assertEquals("before publication", Files.readString(existing, StandardCharsets.UTF_8));
		}
	}

	@Test
	void rejectsCorruptExistingObjectWithoutOverwritingIt() throws Exception {
		Path objects = tempDir.resolve("host-generations/objects");
		try (ModpackCandidate candidate = scan()) {
			StagedObject staged = onlyObject(candidate);
			Path existing = objects.resolve(staged.sha1());
			Files.createDirectories(objects);
			Files.writeString(existing, "corrupt", StandardCharsets.UTF_8);

			IOException failure = assertThrows(IOException.class,
					() -> new LegacyCandidatePublisher(tempDir.resolve("catalogue.json"), objects, tempDir.resolve("host-generations/staging"), null).publish(candidate));

			assertTrue(failure.getMessage().contains("Refusing to replace corrupt immutable object"));
			assertEquals("corrupt", Files.readString(existing, StandardCharsets.UTF_8));
			assertFalse(Files.exists(staged.stagedPath(), LinkOption.NOFOLLOW_LINKS));
		}
	}

	@Test
	void catalogueFailureRestoresServingMapButLeavesPromotedObjectUnreachable() throws Exception {
		Path objects = tempDir.resolve("host-generations/objects");
		Path blockedCatalogue = tempDir.resolve("catalogue-blocked");
		Files.createDirectory(blockedCatalogue);
		Path previousPath = tempDir.resolve("previous-object");
		Files.writeString(previousPath, "previous", StandardCharsets.UTF_8);
		NettyServer server = new NettyServer();
		Map<String, Path> previous = Map.of("previous", previousPath);
		server.replacePaths(previous);

		try (ModpackCandidate candidate = scan()) {
			StagedObject staged = onlyObject(candidate);
			IOException failure = assertThrows(IOException.class,
					() -> new LegacyCandidatePublisher(blockedCatalogue, objects, tempDir.resolve("host-generations/staging"), server).publish(candidate));

			assertNotNull(failure);
			assertEquals(previous, server.getPathsSnapshot());
			Path promoted = objects.resolve(staged.sha1());
			assertTrue(Files.isRegularFile(promoted, LinkOption.NOFOLLOW_LINKS));
			assertEquals(staged.sha1(), HashUtils.getHash(promoted));
			assertFalse(server.getPath(staged.sha1()).isPresent());
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
		Jsons.GroupDeclaration main = new Jsons.GroupDeclaration();
		main.syncedFiles = Set.of();
		var request = new ModpackCandidateScanner.Request("abc1234", "Test", "1", "fabric", "1", "1", tempDir.resolve("server"),
				tempDir.resolve("groups"), Map.of("main", main), Map.of(), Set.of(), false, false, tempDir.resolve("host-generations/staging"), direct);
		return new ModpackCandidateScanner().scan(request);
	}

	private static StagedObject onlyObject(ModpackCandidate candidate) {
		assertEquals(1, candidate.objects().size());
		return candidate.objects().firstEntry().getValue();
	}
}
