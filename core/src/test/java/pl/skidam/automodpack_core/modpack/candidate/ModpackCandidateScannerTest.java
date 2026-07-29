package pl.skidam.automodpack_core.modpack.candidate;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;

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
		Files.createDirectories(groups);
		Files.writeString(server.resolve("config/example.txt"), "shared");
		Map<String, Jsons.GroupDeclaration> declarations = new LinkedHashMap<>();
		declarations.put("visuals", group("/config/**"));
		declarations.put("main", group("/config/**"));

		ModpackCandidate candidate = scan(server, groups, declarations, false);

		assertTrue(candidate.manifest().groups().get("main").files().containsKey("config/example.txt"));
		assertTrue(candidate.manifest().groups().get("visuals").files().containsKey("config/example.txt"));
		assertEquals(1, candidate.hostedPaths().size());
	}

	@Test
	void excludedGroupFileStillShadowsSyncedSource() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(server.resolve("config"));
		Files.createDirectories(groups.resolve("main/config"));
		Files.writeString(server.resolve("config/example.disabled"), "synced");
		Files.writeString(groups.resolve("main/config/example.disabled"), "explicit");

		ModpackCandidate candidate = scan(server, groups, Map.of("main", group("/config/**")), true);

		assertTrue(candidate.manifest().groups().get("main").files().isEmpty());
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

		ModpackCandidate candidate = scan(server, groups, Map.of("main", group()), true);

		assertEquals(ExcludedCandidate.Reason.EMPTY_FILE, candidate.exclusions().get(0).reason());
		assertTrue(candidate.manifest().groups().get("main").files().isEmpty());
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

	private ModpackCandidate scan(Path server, Path groups, Map<String, Jsons.GroupDeclaration> declarations, boolean autoExclude) throws Exception {
		Executor direct = Runnable::run;
		var request = new ModpackCandidateScanner.Request("abc1234", "Test", "1", "fabric", "1", "1", server, groups, declarations, Map.of(), Set.of(),
				autoExclude, false, direct);
		return new ModpackCandidateScanner().scan(request, null);
	}

	private static Jsons.GroupDeclaration group(String... rules) {
		Jsons.GroupDeclaration group = new Jsons.GroupDeclaration();
		group.syncedFiles = new LinkedHashSet<>(List.of(rules));
		return group;
	}
}
