package pl.skidam.automodpack_core.modpack.candidate;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

class ModpackCandidateScannerTest {
	@TempDir
	Path tempDir;

	@Test
	void groupFolderShadowsSyncedSourceAndRecordsProvenance() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(server.resolve("config"));
		Files.createDirectories(groups.resolve("main/config"));
		Files.writeString(server.resolve("config/example.txt"), "synced");
		Files.writeString(groups.resolve("main/config/example.txt"), "explicit");
		Jsons.GroupDeclaration main = group("/config/**");
		main.category = "performance";

		ModpackCandidate candidate = scan(server, groups, Map.of("main", main), false);

		assertEquals(1, candidate.shadows().size());
		assertEquals(CandidateSource.SourceKind.GROUP_DIRECTORY, candidate.shadows().get(0).selected().kind());
		assertEquals("config/example.txt", candidate.manifest().groups().get("main").files().firstKey());
		assertEquals("performance", candidate.manifest().groups().get("main").category());
		assertEquals("performance", candidate.manifest().toFields().groups.get("main").category);
	}

	@Test
	void oneSyncedSourceCanBelongToMultipleGroups() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(server.resolve("config"));
		Files.createDirectories(server.resolve("unrelated/deep"));
		Files.createDirectories(groups);
		Files.writeString(server.resolve("config/example.txt"), "shared");
		Files.writeString(server.resolve("unrelated/deep/example.txt"), "ignored");
		Map<String, Jsons.GroupDeclaration> declarations = new LinkedHashMap<>();
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
		Files.writeString(tempDir.resolve("outside/example.txt"), "outside");

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
		Files.writeString(server.resolve("config/example.disabled"), "synced");
		Files.writeString(server.resolve("config/kept.txt"), "kept");
		Files.writeString(groups.resolve("main/config/example.disabled"), "explicit");

		ModpackCandidate candidate = scan(server, groups, Map.of("main", group("/config/**")), true);

		assertTrue(candidate.manifest().groups().get("main").files().containsKey("config/kept.txt"));
		assertEquals(ExcludedCandidate.Reason.DISABLED_FILE, candidate.exclusions().get(0).reason());
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
		Files.writeString(server.resolve("config/example.txt"), "shared");
		Map<String, Jsons.GroupDeclaration> first = new LinkedHashMap<>();
		first.put("visuals", group("/config/**"));
		first.put("main", group("/config/**"));
		Map<String, Jsons.GroupDeclaration> second = new LinkedHashMap<>();
		second.put("main", group("/config/**"));
		second.put("visuals", group("/config/**"));

		String firstJson = ConfigTools.GSON.toJson(scan(server, groups, first, false).manifest().toFields());
		String secondJson = ConfigTools.GSON.toJson(scan(server, groups, second, false).manifest().toFields());

		assertEquals(firstJson, secondJson);
	}

	@Test
	void sourceMutationAfterEveryCopyExhaustsRetriesWithoutLeakingStagedFiles() throws Exception {
		Path sourcePath = tempDir.resolve("source.txt");
		Files.writeString(sourcePath, "initial", StandardCharsets.UTF_8);
		Path staging = tempDir.resolve("staging");
		CandidateSource source = new CandidateSource("main", "config/source.txt", CandidateSource.SourceKind.GROUP_DIRECTORY, sourcePath, null);
		StableSourceSnapshotter reader = new StableSourceSnapshotter((sourceFile, staged) -> {
			Files.copy(sourceFile, staged, StandardCopyOption.REPLACE_EXISTING);
			Files.writeString(sourceFile, Files.readString(sourceFile, StandardCharsets.UTF_8) + "x", StandardCharsets.UTF_8);
		});

		CandidateBuildException failure = assertThrows(CandidateBuildException.class, () -> reader.snapshot(source, false, false, staging));

		assertTrue(failure.getMessage().contains("remained unstable"));
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

	private ModpackCandidate scan(Path server, Path groups, Map<String, Jsons.GroupDeclaration> declarations, boolean autoExclude) throws Exception {
		Executor direct = Runnable::run;
		var request = new ModpackCandidateScanner.Request("abc1234", "Test", "1", "fabric", "1", "1", server, groups, declarations, Map.of(),
				autoExclude, false, tempDir.resolve("staging"), direct);
		return new ModpackCandidateScanner().scan(request);
	}

	private static Jsons.GroupDeclaration group(String... rules) {
		Jsons.GroupDeclaration group = new Jsons.GroupDeclaration();
		group.syncedFiles = new LinkedHashSet<>(List.of(rules));
		return group;
	}
}
