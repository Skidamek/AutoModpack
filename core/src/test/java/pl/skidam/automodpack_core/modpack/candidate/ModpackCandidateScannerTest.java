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

import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.modpack.generation.ContentTree;
import pl.skidam.automodpack_core.modpack.group.ServerSideModExclusion;
import pl.skidam.automodpack_core.platforms.ModrinthAPI;
import pl.skidam.automodpack_core.platforms.PlatformCache;
import pl.skidam.automodpack_core.platforms.PlatformSideVerdicts;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

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

		ModpackCandidate candidate = scan(server, groups, Map.of("main", group()));
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

		ModpackCandidate candidate = scan(server, groups, Map.of("main", main));

		CandidateProvenance provenance = candidate.provenance().get(ModpackCandidate.provenanceKey("main", "config/example.txt"));
		assertNotNull(provenance);
		assertEquals(CandidateSource.SourceKind.GROUP_DIRECTORY, provenance.selectedSource().kind());
		assertEquals("config/example.txt", provenance.selectedSource().logicalPath());
		assertEquals("config/example.txt", candidate.manifest().groups().get("main").files().keySet().iterator().next());
		assertEquals("General", candidate.manifest().groups().get("main").category());
		assertEquals("main", candidate.manifest().toFields().categories.get("General").keySet().iterator().next());
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

		ModpackCandidate candidate = scan(server, groups, declarations);

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
				() -> scan(server, groups, Map.of("main", group("../outside/**"))));

		assertTrue(failure.getMessage().contains("escapes server root"));
	}

	@Test
	void excludedGroupFileStillShadowsSyncedSource() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(server.resolve("config"));
		Files.createDirectories(groups.resolve("main/config"));
		Files.writeString(server.resolve("config/example.txt"), "synced", StandardCharsets.UTF_8);
		Files.writeString(server.resolve("config/kept.txt"), "kept", StandardCharsets.UTF_8);
		Files.writeString(groups.resolve("main/config/example.txt"), "explicit", StandardCharsets.UTF_8);
		ServerConfigJsons.GroupDeclaration main = group("/config/**");
		main.exclude = new LinkedHashSet<>(List.of("config/example.txt"));

		ModpackCandidate candidate = scan(server, groups, Map.of("main", main));

		assertTrue(candidate.manifest().groups().get("main").files().containsKey("config/kept.txt"));
		assertFalse(candidate.manifest().groups().get("main").files().containsKey("config/example.txt"));
		assertEquals(2, candidate.exclusions().size());
		for (ExcludedCandidate exclusion : candidate.exclusions()) {
			assertEquals(ExcludedCandidate.Reason.EXCLUDED_BY_RULE, exclusion.reason());
			assertEquals("config/example.txt", exclusion.source().logicalPath());
			assertEquals("excluded by config/example.txt", exclusion.message());
		}
	}

	@Test
	void expectedExclusionIsRecordedWithoutPartialFailure() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main/config"));
		Files.createDirectories(server);
		Files.createFile(groups.resolve("main/config/aux.txt"));

		ModpackCandidate candidate = scan(server, groups, Map.of("main", group()));

		assertEquals(1, candidate.exclusions().size());
		assertEquals(ExcludedCandidate.Reason.RESERVED_WINDOWS_NAME, candidate.exclusions().get(0).reason());
		assertEquals(0, candidate.manifest().groups().get("main").files().size());
	}

	/**
	 * The scanner describes what is on disk, so a server with nothing to publish is a truthful empty candidate rather
	 * than a failure. Refusing to publish one is the executor's policy and lives with it.
	 */
	@Test
	void nothingToPublishIsACandidateNotAFailure() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main/config"));
		Files.createDirectories(server);

		ModpackCandidate candidate = scan(server, groups, Map.of("main", group()));

		assertEquals(1, candidate.manifest().groups().size());
		assertEquals(0, candidate.manifest().groups().get("main").files().size());
		assertTrue(ContentTree.tokenOf(candidate.manifest()) != null);
	}

	@Test
	void declarationOrderSurvivesTheScan() throws Exception {
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

		assertEquals(List.of("visuals", "main"), new ArrayList<>(scan(server, groups, first).manifest().groups().keySet()));
		assertEquals(List.of("main", "visuals"), new ArrayList<>(scan(server, groups, second).manifest().groups().keySet()));
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
			return HashUtils.getHash(staged);
		});

		CandidateBuildException failure = assertThrows(CandidateBuildException.class, () -> reader.snapshot(source, false, staging, null, null, null, true));

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
			return HashUtils.getHash(staged);
		});

		CandidateBuildException failure = assertThrows(CandidateBuildException.class, () -> snapshotter.snapshot(source, false, staging, null, null, null, true));

		assertTrue(failure.getMessage().contains("snapshot"));
		assertEquals(original, Files.readString(sourcePath, StandardCharsets.UTF_8));
		assertTrue(Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS));
		try (var files = Files.list(staging)) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void identityOnlySnapshotDoesNotCopy() throws Exception {
		Path pack = tempDir.resolve("resourcepacks/pack.zip");
		Files.createDirectories(pack.getParent());
		Files.write(pack, new byte[4096]);
		AtomicInteger copies = new AtomicInteger();
		CandidateSource source = new CandidateSource("main", "resourcepacks/pack.zip", CandidateSource.SourceKind.GROUP_DIRECTORY, pack, null);
		StableSourceSnapshotter snapshotter = new StableSourceSnapshotter((sourceFile, staged) -> {
			copies.incrementAndGet();
			return HashUtils.copyAndSha1(sourceFile, staged);
		});
		try (FileCache cache = FileCache.open(tempDir.resolve("file-cache"))) {
			StableSourceSnapshotter.Snapshot first = snapshotter.snapshot(source, false, tempDir.resolve("staging"), cache, null, tempDir.resolve("objects"), false);
			StableSourceSnapshotter.Snapshot second = snapshotter.snapshot(source, false, tempDir.resolve("staging"), cache, null, tempDir.resolve("objects"), false);
			assertNotNull(first.file().sha1());
			assertNotNull(first.file().murmur());
			assertNull(first.object());
			assertEquals(first.file().sha1(), second.file().sha1());
			assertEquals(first.file().murmur(), second.file().murmur());
			assertEquals(0, copies.get());
		}
	}

	@Test
	void trustedCasObjectIsNotRestaged() throws Exception {
		Path pack = tempDir.resolve("resourcepacks/pack.zip");
		Files.createDirectories(pack.getParent());
		Files.write(pack, new byte[4096]);
		Path staging = tempDir.resolve("staging");
		Path objects = tempDir.resolve("objects");
		AtomicInteger copies = new AtomicInteger();
		CandidateSource source = new CandidateSource("main", "resourcepacks/pack.zip", CandidateSource.SourceKind.GROUP_DIRECTORY, pack, null);
		StableSourceSnapshotter snapshotter = new StableSourceSnapshotter((sourceFile, staged) -> {
			copies.incrementAndGet();
			return HashUtils.copyAndSha1(sourceFile, staged);
		});
		try (FileCache cache = FileCache.open(tempDir.resolve("file-cache"))) {
			StableSourceSnapshotter.Snapshot first = snapshotter.snapshot(source, false, staging, cache, null, objects, true);
			assertNotNull(first.object());
			Path object = DataRootResolver.objectFile(objects, first.file().sha1());
			Files.createDirectories(object.getParent());
			Files.move(first.object().stagedPath(), object);
			StableSourceSnapshotter.Snapshot second = snapshotter.snapshot(source, false, staging, cache, null, objects, true);
			assertNull(second.object());
			assertEquals(first.file().sha1(), second.file().sha1());
			assertEquals(1, copies.get());
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

		assertThrows(CandidateBuildException.class, () -> new StableSourceSnapshotter().snapshot(source, false, staging, null, null, null, true));
		assertFalse(Files.exists(staging, LinkOption.NOFOLLOW_LINKS));
	}

	@Test
	void reservedWindowsNamesAreExcludedWhereverTheyAppear() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main/config/CON"));
		Files.writeString(groups.resolve("main/config/CON/nested.txt"), "under a reserved directory name", StandardCharsets.UTF_8);
		Files.writeString(groups.resolve("main/config/aux.tar.gz"), "reserved file stem", StandardCharsets.UTF_8);
		Files.writeString(groups.resolve("main/config/kept.txt"), "kept", StandardCharsets.UTF_8);

		ModpackCandidate candidate = scan(server, groups, Map.of("main", group("/config/**")));

		assertTrue(candidate.manifest().groups().get("main").files().containsKey("config/kept.txt"));
		assertEquals(2, candidate.exclusions().size());
		for (ExcludedCandidate exclusion : candidate.exclusions()) {
			assertEquals(ExcludedCandidate.Reason.RESERVED_WINDOWS_NAME, exclusion.reason());
			assertTrue(exclusion.message().contains("Windows clients"));
		}
	}

	private ModpackCandidate scan(Path server, Path groups, Map<String, ServerConfigJsons.GroupDeclaration> declarations) throws Exception {
		return scan(server, groups, declarations, true, null);
	}

	private ModpackCandidate scan(Path server, Path groups, Map<String, ServerConfigJsons.GroupDeclaration> declarations, boolean applyPlatformExclusions, PlatformSideVerdicts sideVerdicts)
			throws Exception {
		Executor direct = Runnable::run;
		var request = new ModpackCandidateScanner.Request("abc1234", "Test", "1", "fabric", "1", "1", server, groups, Map.of("General", declarations), applyPlatformExclusions, tempDir.resolve("staging"),
				direct, null, null, null, true);
		return new ModpackCandidateScanner(sideVerdicts).scan(request);
	}

	/** A platform cache that already answers for {@code jar} with the given Modrinth version environment, so the stage never asks the network. */
	private PlatformSideVerdicts cacheAnsweringModrinth(Path jar, String environment) throws IOException {
		Path cacheDirectory = tempDir.resolve("platform-cache");
		try (PlatformCache cache = PlatformCache.open(cacheDirectory)) {
			cache.putModrinth(HashUtils.getHash(jar), new ModrinthAPI("mod", null, "https://cdn.example/a.jar", "1.0.0", "a.jar", 1L, "release", HashUtils.getHash(jar), environment), null);
		}
		return new PlatformSideVerdicts(cacheDirectory);
	}

	private static ServerConfigJsons.GroupDeclaration group(String... rules) {
		ServerConfigJsons.GroupDeclaration group = new ServerConfigJsons.GroupDeclaration();
		group.fromServer = new LinkedHashSet<>(List.of(rules));
		return group;
	}

	@Test
	void excludeLeaveGroupDirectoryContentOutOfThePack() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main/config"));
		Files.writeString(groups.resolve("main/config/kept.txt"), "kept", StandardCharsets.UTF_8);
		Files.writeString(groups.resolve("main/pakku-params.json"), "params", StandardCharsets.UTF_8);
		Files.writeString(groups.resolve("main/pakku-cache.json"), "cache", StandardCharsets.UTF_8);
		ServerConfigJsons.GroupDeclaration main = group();
		main.exclude = new LinkedHashSet<>(List.of("pakku-*.json"));

		ModpackCandidate candidate = scan(server, groups, Map.of("main", main));
		var files = candidate.manifest().groups().get("main").files();

		assertTrue(files.containsKey("config/kept.txt"));
		assertFalse(files.containsKey("pakku-params.json"));
		assertFalse(files.containsKey("pakku-cache.json"));
		assertEquals(2, candidate.exclusions().size());
		for (ExcludedCandidate exclusion : candidate.exclusions()) {
			assertEquals(ExcludedCandidate.Reason.EXCLUDED_BY_RULE, exclusion.reason());
			assertEquals("excluded by pakku-*.json", exclusion.message());
		}
	}

	@Test
	void excludeCarveExceptionsOutOfSyncedRules() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(server.resolve("kubejs/server_scripts"));
		Files.writeString(server.resolve("kubejs/main.js"), "script", StandardCharsets.UTF_8);
		Files.writeString(server.resolve("kubejs/server_scripts/secret.js"), "secret", StandardCharsets.UTF_8);
		ServerConfigJsons.GroupDeclaration main = group("kubejs/**");
		main.exclude = new LinkedHashSet<>(List.of("kubejs/server_scripts/**"));

		ModpackCandidate candidate = scan(server, groups, Map.of("main", main));
		var files = candidate.manifest().groups().get("main").files();

		assertTrue(files.containsKey("kubejs/main.js"));
		assertFalse(files.containsKey("kubejs/server_scripts/secret.js"));
		assertEquals(1, candidate.exclusions().size());
		assertEquals(ExcludedCandidate.Reason.EXCLUDED_BY_RULE, candidate.exclusions().get(0).reason());
	}

	@Test
	void negatedSyncedRuleSparesOnlyTheSyncedCopy() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(server.resolve("config"));
		Files.createDirectories(groups.resolve("main/config"));
		Files.writeString(server.resolve("config/dup.txt"), "server build", StandardCharsets.UTF_8);
		Files.writeString(groups.resolve("main/config/dup.txt"), "client build", StandardCharsets.UTF_8);
		ServerConfigJsons.GroupDeclaration main = group("config/**", "!config/dup.txt");

		ModpackCandidate candidate = scan(server, groups, Map.of("main", main));
		var files = candidate.manifest().groups().get("main").files();

		// The synced copy is skipped, but the group directory still provides the path - only exclude could remove it entirely.
		assertTrue(files.containsKey("config/dup.txt"));
		assertEquals(0, candidate.exclusions().size());
	}

	@Test
	void negatedExclusionRulesSparePathsFromTheExclusionSet() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main"));
		Files.writeString(groups.resolve("main/pakku-params.json"), "params", StandardCharsets.UTF_8);
		Files.writeString(groups.resolve("main/pakku-keep.json"), "keep", StandardCharsets.UTF_8);
		ServerConfigJsons.GroupDeclaration main = group();
		main.exclude = new LinkedHashSet<>(List.of("pakku-*.json", "!pakku-keep.json"));

		ModpackCandidate candidate = scan(server, groups, Map.of("main", main));
		var files = candidate.manifest().groups().get("main").files();

		assertTrue(files.containsKey("pakku-keep.json"));
		assertFalse(files.containsKey("pakku-params.json"));
		assertEquals(1, candidate.exclusions().size());
		assertEquals("excluded by pakku-*.json", candidate.exclusions().get(0).message());
	}

	@Test
	void negatedEditRulesKeepServerOwnedPaths() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main/config/fancymenu"));
		Files.writeString(groups.resolve("main/config/options.txt"), "options", StandardCharsets.UTF_8);
		Files.writeString(groups.resolve("main/config/fancymenu/theme.txt"), "theme", StandardCharsets.UTF_8);
		ServerConfigJsons.GroupDeclaration main = group();
		main.editable = new LinkedHashSet<>(List.of("config/**", "!config/fancymenu/**"));

		ModpackCandidate candidate = scan(server, groups, Map.of("main", main));
		var files = candidate.manifest().groups().get("main").files();

		assertTrue(files.get("config/options.txt").editable());
		assertFalse(files.get("config/fancymenu/theme.txt").editable());
	}

	@Test
	void reservedWindowsNamesStayExcludedWithoutAnyRules() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main/config"));
		Files.writeString(groups.resolve("main/config/aux.tar.gz"), "reserved", StandardCharsets.UTF_8);
		Files.writeString(groups.resolve("main/config/old.bak"), "backup", StandardCharsets.UTF_8);

		ModpackCandidate candidate = scan(server, groups, Map.of("main", group()));
		var files = candidate.manifest().groups().get("main").files();

		assertFalse(files.containsKey("config/aux.tar.gz"));
		assertTrue(files.containsKey("config/old.bak"));
		assertEquals(1, candidate.exclusions().size());
		assertEquals(ExcludedCandidate.Reason.RESERVED_WINDOWS_NAME, candidate.exclusions().get(0).reason());
	}

	@Test
	void emptyFilesArePackContent() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main/config"));
		Files.createFile(groups.resolve("main/config/marker"));

		ModpackCandidate candidate = scan(server, groups, Map.of("main", group()));
		var files = candidate.manifest().groups().get("main").files();

		assertEquals(1, files.size());
		assertEquals(0L, files.get("config/marker").size());
		assertEquals(0, candidate.exclusions().size());
	}

	@Test
	void freshConfigDefaultsFilterJunkThroughSeededRules() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main/config"));
		Files.writeString(groups.resolve("main/config/old.bak"), "backup", StandardCharsets.UTF_8);
		Files.writeString(groups.resolve("main/config/.gitkeep"), "keep", StandardCharsets.UTF_8);
		Files.writeString(groups.resolve("main/config/kept.txt"), "kept", StandardCharsets.UTF_8);

		ModpackCandidate candidate = scan(server, groups, new ServerConfigJsons.ServerConfigFieldsV3().modpack.categories.get("General"));
		var files = candidate.manifest().groups().get("main").files();

		assertTrue(files.containsKey("config/kept.txt"));
		assertFalse(files.containsKey("config/old.bak"));
		assertFalse(files.containsKey("config/.gitkeep"));
		assertEquals(2, candidate.exclusions().size());
		for (ExcludedCandidate exclusion : candidate.exclusions()) {
			assertEquals(ExcludedCandidate.Reason.EXCLUDED_BY_RULE, exclusion.reason());
			assertFalse(exclusion.message().isBlank());
		}
	}

	/** The jar's own metadata answers this one, so the scan never reaches a platform stage at all. */
	@Test
	void jarServerEnvironmentIsExcludedWithoutAskingPlatforms() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main"));
		writeModJar(groups.resolve("main/server-only.jar"), "server");

		ServerConfigJsons.GroupDeclaration main = group();
		main.autoExcludeServerSideMods = ServerSideModExclusion.METADATA;
		ModpackCandidate candidate = scan(server, groups, Map.of("main", main));

		assertFalse(candidate.manifest().groups().get("main").files().containsKey("server-only.jar"));
		assertEquals(1, candidate.exclusions().size());
		assertEquals(ExcludedCandidate.Reason.SERVER_SIDE_MOD, candidate.exclusions().get(0).reason());
		assertEquals("detected as a server-side mod", candidate.exclusions().get(0).message());
	}

	/** A displayTest of IGNORE_SERVER_VERSION is the jar's own word that clients can run without it, so the verdict needs no platform. */
	@Test
	void forgeJarDeclaringIgnoreServerVersionIsExcludedWithoutAskingPlatforms() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main"));
		writeForgeJar(groups.resolve("main/declared-server-only.jar"), "IGNORE_SERVER_VERSION", false);

		ServerConfigJsons.GroupDeclaration main = group();
		main.autoExcludeServerSideMods = ServerSideModExclusion.METADATA;
		ModpackCandidate candidate = scan(server, groups, Map.of("main", main));

		assertFalse(candidate.manifest().groups().get("main").files().containsKey("declared-server-only.jar"));
		assertEquals(1, candidate.exclusions().size());
		assertEquals(ExcludedCandidate.Reason.SERVER_SIDE_MOD, candidate.exclusions().get(0).reason());
	}

	/** A Forge jar on MATCH_VERSION (the default, here because the key is absent) gets joining clients kicked, so a platform verdict must not drop it. */
	@Test
	void forgeJarWithoutDisplayTestStaysEvenWhenModrinthSaysDedicatedServerOnly() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main"));
		Path jar = groups.resolve("main/matched-version.jar");
		writeForgeJar(jar, null, false);

		ServerConfigJsons.GroupDeclaration main = group();
		main.autoExcludeServerSideMods = ServerSideModExclusion.PLATFORMS;
		ModpackCandidate candidate = scan(server, groups, Map.of("main", main), true, cacheAnsweringModrinth(jar, "dedicated_server_only"));

		assertTrue(candidate.manifest().groups().get("main").files().containsKey("matched-version.jar"), "excluding a jar clients must carry would kick them on join");
		assertEquals(0, candidate.exclusions().size());
	}

	/** Nested JarJar jars carry displayTest declarations of their own, which the root entry cannot speak for. */
	@Test
	void nestedJarsWithholdTheDisplayTestPromise() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main"));
		writeForgeJar(groups.resolve("main/nested-server-only.jar"), "IGNORE_SERVER_VERSION", true);

		ServerConfigJsons.GroupDeclaration main = group();
		main.autoExcludeServerSideMods = ServerSideModExclusion.METADATA;
		ModpackCandidate candidate = scan(server, groups, Map.of("main", main));

		assertTrue(candidate.manifest().groups().get("main").files().containsKey("nested-server-only.jar"));
		assertEquals(0, candidate.exclusions().size());
	}

	@Test
	void universalJarIsExcludedWhenModrinthSaysClientsDoNotRunIt() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main"));
		Path universal = groups.resolve("main/universal.jar");
		writeModJar(universal);

		ServerConfigJsons.GroupDeclaration main = group();
		main.autoExcludeServerSideMods = ServerSideModExclusion.PLATFORMS;
		ModpackCandidate candidate = scan(server, groups, Map.of("main", main), true, cacheAnsweringModrinth(universal, "dedicated_server_only"));

		assertFalse(candidate.manifest().groups().get("main").files().containsKey("universal.jar"));
		assertEquals(1, candidate.exclusions().size());
		assertEquals(ExcludedCandidate.Reason.SERVER_SIDE_MOD, candidate.exclusions().get(0).reason());
		assertEquals("modrinth environment dedicated_server_only", candidate.exclusions().get(0).message());
	}

	@Test
	void serverOnlyModrinthEnvironmentStaysInThePack() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main"));
		Path universal = groups.resolve("main/universal.jar");
		writeModJar(universal);

		ServerConfigJsons.GroupDeclaration main = group();
		main.autoExcludeServerSideMods = ServerSideModExclusion.PLATFORMS;
		ModpackCandidate candidate = scan(server, groups, Map.of("main", main), true, cacheAnsweringModrinth(universal, "server_only"));

		assertTrue(candidate.manifest().groups().get("main").files().containsKey("universal.jar"));
		assertEquals(0, candidate.exclusions().size());
	}

	@Test
	void previewReportsThePlatformExclusionWithoutDroppingTheJar() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main"));
		Path universal = groups.resolve("main/universal.jar");
		writeModJar(universal);

		ServerConfigJsons.GroupDeclaration main = group();
		main.autoExcludeServerSideMods = ServerSideModExclusion.PLATFORMS;
		ModpackCandidate candidate = scan(server, groups, Map.of("main", main), false, cacheAnsweringModrinth(universal, "dedicated_server_only"));

		assertTrue(candidate.manifest().groups().get("main").files().containsKey("universal.jar"), "a preview reports the verdict, only a publish applies it");
		assertEquals(1, candidate.exclusions().size());
		assertEquals(ExcludedCandidate.Reason.SERVER_SIDE_MOD, candidate.exclusions().get(0).reason());
	}

	@Test
	void disabledModeKeepsAJarTheMetadataWouldDrop() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main"));
		writeModJar(groups.resolve("main/server-only.jar"), "server");

		ServerConfigJsons.GroupDeclaration main = group();
		main.autoExcludeServerSideMods = ServerSideModExclusion.DISABLED;
		ModpackCandidate candidate = scan(server, groups, Map.of("main", main));

		assertTrue(candidate.manifest().groups().get("main").files().containsKey("server-only.jar"));
		assertEquals(0, candidate.exclusions().size());
	}

	@Test
	void metadataModeKeepsAUniversalJarThePlatformWouldDrop() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main"));
		Path universal = groups.resolve("main/universal.jar");
		writeModJar(universal);

		ModpackCandidate candidate = scan(server, groups, Map.of("main", group()), true, cacheAnsweringModrinth(universal, "dedicated_server_only"));

		assertTrue(candidate.manifest().groups().get("main").files().containsKey("universal.jar"));
		assertEquals(0, candidate.exclusions().size());
	}

	@Test
	void platformExclusionAppliesOnlyToTheGroupThatAsked() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main"));
		Files.createDirectories(groups.resolve("push"));
		Path universal = groups.resolve("main/universal.jar");
		writeModJar(universal);
		Files.copy(universal, groups.resolve("push/universal.jar"));

		ServerConfigJsons.GroupDeclaration main = group();
		main.autoExcludeServerSideMods = ServerSideModExclusion.PLATFORMS;
		ServerConfigJsons.GroupDeclaration push = group();
		push.autoExcludeServerSideMods = ServerSideModExclusion.METADATA;
		ModpackCandidate candidate = scan(server, groups, Map.of("main", main, "push", push), true, cacheAnsweringModrinth(universal, "dedicated_server_only"));

		assertFalse(candidate.manifest().groups().get("main").files().containsKey("universal.jar"));
		assertTrue(candidate.manifest().groups().get("push").files().containsKey("universal.jar"));
	}

	@Test
	void neoforgeJarWithMinecraftSideServerStaysUnderMetadata() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main"));
		writeNeoForgeJar(groups.resolve("main/worldgen.jar"), "SERVER");

		ServerConfigJsons.GroupDeclaration main = group();
		main.autoExcludeServerSideMods = ServerSideModExclusion.METADATA;
		ModpackCandidate candidate = scan(server, groups, Map.of("main", main));

		assertTrue(candidate.manifest().groups().get("main").files().containsKey("worldgen.jar"));
		assertEquals(0, candidate.exclusions().size());
	}

	@Test
	void neoforgeJarWithMinecraftSideServerIsDroppedWhenModrinthSaysDedicatedServerOnly() throws Exception {
		Path server = tempDir.resolve("server");
		Path groups = tempDir.resolve("groups");
		Files.createDirectories(groups.resolve("main"));
		Path jar = groups.resolve("main/dedicated.jar");
		writeNeoForgeJar(jar, "SERVER");

		ServerConfigJsons.GroupDeclaration main = group();
		main.autoExcludeServerSideMods = ServerSideModExclusion.PLATFORMS;
		ModpackCandidate candidate = scan(server, groups, Map.of("main", main), true, cacheAnsweringModrinth(jar, "dedicated_server_only"));

		assertFalse(candidate.manifest().groups().get("main").files().containsKey("dedicated.jar"));
		assertEquals(1, candidate.exclusions().size());
		assertEquals("modrinth environment dedicated_server_only", candidate.exclusions().get(0).message());
	}

	private static void writeModJar(Path path) throws IOException {
		writeModJar(path, null);
	}

	private static void writeModJar(Path path, String environment) throws IOException {
		String json = environment == null
				? "{\"schemaVersion\":1,\"id\":\"testmod\",\"version\":\"1.0.0\"}"
				: "{\"schemaVersion\":1,\"id\":\"testmod\",\"version\":\"1.0.0\",\"environment\":\"" + environment + "\"}";
		try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
			jar.putNextEntry(new JarEntry("fabric.mod.json"));
			jar.write(json.getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();
		}
	}

	private static void writeNeoForgeJar(Path path, String minecraftSide) throws IOException {
		String toml = "modLoader = \"javafml\"\nloaderVersion = \"[1,)\"\n[[mods]]\nmodId = \"testmod\"\nversion = \"1.0.0\"\n[[dependencies.testmod]]\nmodId = \"minecraft\"\ntype = \"required\"\nside = \""
				+ minecraftSide + "\"\n";
		try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
			jar.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml"));
			jar.write(toml.getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();
		}
	}

	private static void writeForgeJar(Path path, String displayTest, boolean nestedJar) throws IOException {
		String toml = "modLoader = \"javafml\"\nloaderVersion = \"[47,)\"\n[[mods]]\nmodId = \"testmod\"\nversion = \"1.0.0\"\n"
				+ (displayTest == null ? "" : "displayTest = \"" + displayTest + "\"\n");
		try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
			jar.putNextEntry(new JarEntry("META-INF/mods.toml"));
			jar.write(toml.getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();
			if (nestedJar) {
				jar.putNextEntry(new JarEntry("META-INF/jarjar/nested.jar"));
				jar.write(EMPTY_ZIP);
				jar.closeEntry();
			}
		}
	}

	private static final byte[] EMPTY_ZIP = {0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
}
