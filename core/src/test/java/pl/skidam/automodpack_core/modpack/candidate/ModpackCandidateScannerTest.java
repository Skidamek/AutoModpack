package pl.skidam.automodpack_core.modpack.candidate;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ServerConfigJsons;

class ModpackCandidateScannerTest {
	@TempDir
	Path tempDir;

	@Test
	void classifiesModsByContentAndPacksByPath() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Path resourcepacks = groups.resolve("main/resourcepacks");
		Path shaderpacks = groups.resolve("main/shaderpacks");
		Files.createDirectories(server);
		Files.createDirectories(resourcepacks);
		Files.createDirectories(shaderpacks);
		writeModJar(resourcepacks.resolve("mod-shaped.jar"));
		Files.writeString(resourcepacks.resolve("pack.zip"), "resource pack", StandardCharsets.UTF_8);
		Files.writeString(shaderpacks.resolve("shader.zip"), "shader pack", StandardCharsets.UTF_8);

		ModpackCandidate candidate = scan(server, groups, Map.of("main", group()), false);
		var files = candidate.manifest().groups().get("main").files();

		assertEquals("mod", files.get("resourcepacks/mod-shaped.jar").type());
		assertEquals("resourcepack", files.get("resourcepacks/pack.zip").type());
		assertEquals("shader", files.get("shaderpacks/shader.zip").type());
	}

	@Test
	void groupFolderShadowsSyncedSourceAndRecordsProvenance() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(server.resolve("config"));
		Files.createDirectories(groups.resolve("main/config"));
		Files.writeString(server.resolve("config/example.txt"), "synced", StandardCharsets.UTF_8);
		Files.writeString(groups.resolve("main/config/example.txt"), "explicit", StandardCharsets.UTF_8);
		ServerConfigJsons.GroupDeclaration main = group("/config/**");

		ModpackCandidate candidate = scan(server, groups, Map.of("main", main), false);

		assertEquals(1, candidate.shadows().size());
		ShadowedCandidate shadow = candidate.shadows().get(0);
		assertEquals(CandidateSource.SourceKind.GROUP_DIRECTORY, shadow.selected().kind());
		assertEquals(CandidateSource.SourceKind.SYNCED_ROOT, shadow.shadowed().kind());
		assertEquals(ShadowedCandidate.Relationship.NOT_COMPARED, shadow.relationship());
		CandidateProvenance provenance = candidate.provenance().get(ModpackCandidate.provenanceKey("main", "config/example.txt"));
		assertNotNull(provenance);
		assertEquals(CandidateSource.SourceKind.GROUP_DIRECTORY, provenance.selectedSource().kind());
		assertEquals("config/example.txt", provenance.selectedSource().logicalPath());
		assertEquals("config/example.txt", candidate.manifest().groups().get("main").files().firstKey());
		assertEquals("", candidate.manifest().groups().get("main").category());
		assertEquals("", candidate.manifest().toFields().groups.get("main").category);
	}

	@Test
	void oneSyncedSourceCanBelongToMultipleGroups() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(server.resolve("config"));
		Files.createDirectories(server.resolve("unrelated/deep"));
		Files.createDirectories(groups);
		Files.writeString(server.resolve("config/example.txt"), "shared", StandardCharsets.UTF_8);
		Files.writeString(server.resolve("unrelated/deep/example.txt"), "ignored", StandardCharsets.UTF_8);
		Map<String, ServerConfigJsons.GroupDeclaration> declarations = new LinkedHashMap<>();
		declarations.put("visuals", group("/config/**"));
		declarations.put("main", group("/config/**"));

		ModpackCandidate candidate = scan(server, groups, declarations, false);

		assertTrue(candidate.manifest().groups().get("main").files().containsKey("config/example.txt"));
		assertTrue(candidate.manifest().groups().get("visuals").files().containsKey("config/example.txt"));
		assertEquals(1, candidate.objects().size());
	}

	@Test
	void synchronizedRulesCannotScanOutsideServerRoot() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(server);
		Files.createDirectories(groups);
		Files.createDirectories(tempDir.resolve("outside"));
		Files.writeString(tempDir.resolve("outside/example.txt"), "outside", StandardCharsets.UTF_8);

		CandidateBuildException failure = assertThrows(CandidateBuildException.class,
				() -> scan(server, groups, Map.of("main", group("../outside/**")), false));

		assertTrue(failure.getMessage().contains("escapes server root"));
	}

	@Test
	void excludedGroupFileStillShadowsSyncedSource() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(server.resolve("config"));
		Files.createDirectories(groups.resolve("main/config"));
		Files.writeString(server.resolve("config/example.disabled"), "synced", StandardCharsets.UTF_8);
		Files.writeString(server.resolve("config/kept.txt"), "kept", StandardCharsets.UTF_8);
		Files.writeString(groups.resolve("main/config/example.disabled"), "explicit", StandardCharsets.UTF_8);

		ModpackCandidate candidate = scan(server, groups, Map.of("main", group("/config/**")), true);

		assertTrue(candidate.manifest().groups().get("main").files().containsKey("config/kept.txt"));
		ExcludedCandidate exclusion = candidate.exclusions().get(0);
		assertEquals(ExcludedCandidate.Reason.DISABLED_FILE, exclusion.reason());
		assertEquals("config/example.disabled", exclusion.source().logicalPath());
		assertFalse(exclusion.message().isBlank());
		assertEquals(1, candidate.shadows().size());
	}

	@Test
	void expectedExclusionIsRecordedWithoutPartialFailure() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main/config"));
		Files.createDirectories(server);
		Files.createFile(groups.resolve("main/config/empty.txt"));

		CandidateBuildException failure = assertThrows(CandidateBuildException.class, () -> scan(server, groups, Map.of("main", group()), true));

		assertTrue(failure.getMessage().contains("no published files"));
		if (Files.exists(tempDir.resolve("staging"))) {
			try (var files = Files.list(tempDir.resolve("staging"))) {
				assertEquals(0, files.count());
			}
		}
	}

	@Test
	void shuffledDeclarationsProduceSameCatalogue() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(server.resolve("config"));
		Files.createDirectories(groups);
		Files.writeString(server.resolve("config/example.txt"), "shared", StandardCharsets.UTF_8);
		Map<String, ServerConfigJsons.GroupDeclaration> first = new LinkedHashMap<>();
		first.put("visuals", group("/config/**"));
		first.put("main", group("/config/**"));
		Map<String, ServerConfigJsons.GroupDeclaration> second = new LinkedHashMap<>();
		second.put("main", group("/config/**"));
		second.put("visuals", group("/config/**"));

		String firstJson = ConfigTools.GSON.toJson(scan(server, groups, first, false).manifest().toFields());
		String secondJson = ConfigTools.GSON.toJson(scan(server, groups, second, false).manifest().toFields());

		assertEquals(firstJson, secondJson);
	}

	@Test
	void sourceMutationDuringCopyFailsWithoutLeakingStagedFiles() throws Exception {
		Path sourcePath = tempDir.resolve("source.txt");
		Files.writeString(sourcePath, "initial", StandardCharsets.UTF_8);
		Path staging = tempDir.resolve("staging");
		CandidateSource source = new CandidateSource("main", "config/source.txt", CandidateSource.SourceKind.GROUP_DIRECTORY, sourcePath, null);
		AtomicInteger copies = new AtomicInteger();
		StableSourceSnapshotter reader = new StableSourceSnapshotter((sourceFile, staged) -> {
			copies.incrementAndGet();
			Files.copy(sourceFile, staged, StandardCopyOption.REPLACE_EXISTING);
			Files.writeString(sourceFile, Files.readString(sourceFile, StandardCharsets.UTF_8) + "x", StandardCharsets.UTF_8);
		});

		CandidateBuildException failure = assertThrows(CandidateBuildException.class, () -> reader.snapshot(source, false, false, staging));

		assertTrue(failure.getMessage().contains("changed while being snapshotted"));
		assertEquals(1, copies.get());
		assertTrue(Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS));
		try (var files = Files.list(staging)) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void truncatedSnapshotFailsWithoutLeakingStagedFile() throws Exception {
		Path sourcePath = tempDir.resolve("source.txt");
		String original = "stable source content";
		Files.writeString(sourcePath, original, StandardCharsets.UTF_8);
		Path staging = tempDir.resolve("staging");
		CandidateSource source = new CandidateSource("main", "config/source.txt", CandidateSource.SourceKind.GROUP_DIRECTORY, sourcePath, null);
		StableSourceSnapshotter snapshotter = new StableSourceSnapshotter((sourceFile, staged) -> {
			byte[] bytes = Files.readAllBytes(sourceFile);
			Files.write(staged, Arrays.copyOf(bytes, bytes.length - 1));
		});

		CandidateBuildException failure = assertThrows(CandidateBuildException.class, () -> snapshotter.snapshot(source, false, false, staging));

		assertTrue(failure.getMessage().contains("snapshot"));
		assertEquals(original, Files.readString(sourcePath, StandardCharsets.UTF_8));
		assertTrue(Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS));
		try (var files = Files.list(staging)) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void symbolicLinkSourceIsRejectedWithoutCreatingStagedObject() throws Exception {
		Path target = tempDir.resolve("target.txt");
		Path sourcePath = tempDir.resolve("source.txt");
		Files.writeString(target, "target", StandardCharsets.UTF_8);
		try {
			Files.createSymbolicLink(sourcePath, target);
		} catch (UnsupportedOperationException | SecurityException | IOException e) {
			Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e);
		}
		Path staging = tempDir.resolve("staging");
		CandidateSource source = new CandidateSource("main", "config/source.txt", CandidateSource.SourceKind.GROUP_DIRECTORY, sourcePath, null);

		assertThrows(CandidateBuildException.class, () -> new StableSourceSnapshotter().snapshot(source, false, false, staging));
		assertFalse(Files.exists(staging, LinkOption.NOFOLLOW_LINKS));
	}

	private ModpackCandidate scan(Path server, Path groups, Map<String, ServerConfigJsons.GroupDeclaration> declarations, boolean autoExclude) throws Exception {
		Executor direct = Runnable::run;
		var request = new ModpackCandidateScanner.Request("abc1234", "Test", "1", "fabric", "1", "1", server, groups, declarations,
				autoExclude, false, tempDir.resolve("staging"), direct);
		return new ModpackCandidateScanner().scan(request);
	}

	private static ServerConfigJsons.GroupDeclaration group(String... rules) {
		ServerConfigJsons.GroupDeclaration group = new ServerConfigJsons.GroupDeclaration();
		group.syncedFiles = new LinkedHashSet<>(List.of(rules));
		return group;
	}

	private static void writeModJar(Path path) throws IOException {
		try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
			jar.putNextEntry(new JarEntry("fabric.mod.json"));
			jar.write("{\"schemaVersion\":1,\"id\":\"testmod\",\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();
		}
	}
}
