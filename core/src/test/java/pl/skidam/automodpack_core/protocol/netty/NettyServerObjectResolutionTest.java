package pl.skidam.automodpack_core.protocol.netty;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.StagedObject;
import pl.skidam.automodpack_core.modpack.generation.GenerationStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.utils.HashUtils;

class NettyServerObjectResolutionTest {
	@TempDir
	Path tempDir;

	@Test
	void blankServesCurrentProjectionAndOnlyActiveObjects() throws Exception {
		GenerationStore store = store();
		GenerationStore.Publication first = publish(store, "first");
		NettyServer server = server(store, first);
		String firstHash = hash(first);
		Path projection = store.objectRoot().getParent().resolve("current-projection.json");

		assertEquals(projection, server.getPath("").orElseThrow());
		assertEquals(store.objectRoot().resolve(firstHash), server.getPath(firstHash.toUpperCase(Locale.ROOT)).orElseThrow());

		GenerationStore.CurrentSnapshot current = store.loadCurrent().orElseThrow();
		GenerationStore.Publication second = publish(store, "second", Optional.of(current));
		server.replacePaths(second.hostingPaths());

		assertEquals(projection, server.getPath("").orElseThrow());
		assertTrue(server.getPath(firstHash).isEmpty());
		assertEquals(store.objectRoot().resolve(hash(second)), server.getPath(hash(second)).orElseThrow());
	}

	@Test
	void rejectsInvalidKeysMissingObjectsAndDirectories() throws Exception {
		TestSetup setup = setup();
		NettyServer server = setup.server();
		String valid = setup.valid();
		Path objects = setup.store().objectRoot();

		assertTrue(server.getPath(" ").isEmpty());
		assertTrue(server.getPath(valid.substring(1)).isEmpty());
		assertTrue(server.getPath(valid + "0").isEmpty());
		assertTrue(server.getPath("g".repeat(40)).isEmpty());
		assertTrue(server.getPath("../" + valid).isEmpty());
		assertTrue(server.getPath(valid + "/child").isEmpty());

		String missingKey = "a".repeat(40);
		assertTrue(server.getPath(missingKey).isEmpty());
		String orphanKey = "d".repeat(40);
		Files.writeString(objects.resolve(orphanKey), "orphan", StandardCharsets.UTF_8);
		assertTrue(server.getPath(orphanKey).isEmpty());

		String directoryKey = "c".repeat(40);
		Files.createDirectory(objects.resolve(directoryKey));
		assertTrue(server.getPath(directoryKey).isEmpty());
	}

	@Test
	void rejectsSymlinks() throws Exception {
		TestSetup setup = setup();
		NettyServer server = setup.server();
		String valid = setup.valid();
		Path objects = setup.store().objectRoot();

		String symlinkKey = "b".repeat(40);
		Path target = tempDir.resolve("symlink-target");
		Files.writeString(target, "not an object", StandardCharsets.UTF_8);
		try {
			Files.createSymbolicLink(objects.resolve(symlinkKey), target);
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e);
		}
		assertTrue(server.getPath(symlinkKey).isEmpty());

		server.replacePaths(Map.of(symlinkKey, objects.resolve(symlinkKey)));
		assertTrue(server.getPath(valid).isEmpty());
		assertTrue(server.getPath(symlinkKey).isEmpty());
	}

	private TestSetup setup() throws Exception {
		GenerationStore store = store();
		GenerationStore.Publication publication = publish(store, "content");
		return new TestSetup(store, server(store, publication), hash(publication));
	}

	private record TestSetup(GenerationStore store, NettyServer server, String valid) {}

	private NettyServer server(GenerationStore store, GenerationStore.Publication publication) {
		NettyServer server = new NettyServer();
		server.replacePaths(publication.hostingPaths());
		return server;
	}

	private GenerationStore store() {
		return new GenerationStore(tempDir.resolve("host-generations"));
	}

	private GenerationStore.Publication publish(GenerationStore store, String description) throws Exception {
		return publish(store, description, store.loadCurrent());
	}

	private GenerationStore.Publication publish(GenerationStore store, String description, Optional<GenerationStore.CurrentSnapshot> current) throws Exception {
		try (ModpackCandidate candidate = candidate(store, description)) {
			return store.publish(candidate, current, "");
		}
	}

	private ModpackCandidate candidate(GenerationStore store, String description) throws Exception {
		byte[] bytes = ("object-" + description).getBytes(StandardCharsets.UTF_8);
		Path staging = store.objectRoot().getParent().resolve("staging");
		Files.createDirectories(staging);
		Path staged = Files.createTempFile(staging, "candidate-", ".staged");
		Files.write(staged, bytes);
		String hash = HashUtils.getHash(staged);
		Jsons.CompleteModpackContentFields fields = new Jsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.selectionTags = Map.of();
		var group = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
		group.description = description;
		group.files = Map.of("config/example.txt", new Jsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(bytes.length), "config", false, false, false, hash, null));
		fields.groups = Map.of("main", group);
		GroupManifest manifest = GroupManifestValidator.validate(fields);
		return new ModpackCandidate(manifest, new TreeMap<>(Map.of(hash, new StagedObject(hash, bytes.length, staged))), new TreeMap<>(), List.of(), List.of());
	}

	private static String hash(GenerationStore.Publication publication) {
		return publication.record().manifest().groups().get("main").files().values().iterator().next().sha1();
	}
}
