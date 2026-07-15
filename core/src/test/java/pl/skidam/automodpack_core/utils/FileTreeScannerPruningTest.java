package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTreeScannerPruningTest {

	@TempDir
	Path rootDir;

	@Test
	void terminalBlacklistExcludesSubtreeAndKeepsSiblings() throws IOException {
		createFile("bluemap/web/tiles/tile.png");
		createFile("bluemap/settings.json");
		createFile("config/mod.toml");
		createFile("root.txt");

		Map<String, Path> matches = scan(Set.of("/**", "!/bluemap/**"));

		assertEquals(Set.of("/config/mod.toml", "/root.txt"), matches.keySet());
	}

	@Test
	void nestedAndWildcardBlacklistPrefixesExcludeOnlyMatchingTrees() throws IOException {
		createFile("config/generated/cache/blob.bin");
		createFile("config/generated/keep.txt");
		createFile("cache-one/blob.bin");
		createFile("cache-two/nested/blob.bin");
		createFile("cached/keep.txt");

		Map<String, Path> matches = scan(Set.of("/**", "!/config/generated/cache/**", "!/cache-*/**"));

		assertEquals(Set.of("/config/generated/keep.txt", "/cached/keep.txt"), matches.keySet());
	}

	@Test
	void nestedDoubleWildcardPrefixExcludesMatchingSubtree() throws IOException {
		createFile("journeymap/world/cache/tiles/blob.bin");
		createFile("journeymap/world/keep.txt");
		createFile("journeymap/other/data.txt");

		Map<String, Path> matches = scan(Set.of("/**", "!/journeymap/**/cache/**"));

		assertEquals(Set.of("/journeymap/world/keep.txt", "/journeymap/other/data.txt"), matches.keySet());
	}

	@Test
	void blacklistSuffixAfterDoubleWildcardDoesNotExcludeWholeSubtree() throws IOException {
		createFile("maps/region/image.png");
		createFile("maps/region/notes.txt");
		createFile("maps/preview.png");

		Map<String, Path> matches = scan(Set.of("/**", "!/maps/**/*.png"));

		assertFalse(matches.containsKey("/maps/region/image.png"));
		assertTrue(matches.containsKey("/maps/region/notes.txt"));
		assertTrue(matches.containsKey("/maps/preview.png"));
	}

	@Test
	void repeatedRecursionAndGlobalBlacklistRemainCorrect() throws IOException {
		createFile("data/nested/blob.bin");
		createFile("keep.txt");

		assertEquals(Set.of("/keep.txt"), scan(Set.of("/**", "!/data/**/**")).keySet());
		assertTrue(scan(Set.of("/**", "!/**")).isEmpty());
	}

	@Test
	void prunableAndEquivalentNonPrunableRulesProduceSameResults() throws IOException {
		createFile("data/cache/root.bin");
		createFile("data/cache/sub/blob.bin");
		createFile("data/keep.txt");

		Map<String, Path> pruned = scan(Set.of("/data/**", "!/data/cache/**"));
		Map<String, Path> nonPruned = scan(Set.of("/data/**", "!/data/cache/*", "!/data/cache/**/*"));

		assertEquals(nonPruned.keySet(), pruned.keySet());
		assertEquals(Set.of("/data/keep.txt"), pruned.keySet());
	}

	@Test
	void broadWhitelistHandlesLargeExcludedTree() throws IOException {
		for (int directory = 0; directory < 50; directory++) {
			for (int file = 0; file < 20; file++) {
				createFile("generated/chunk-" + directory + "/file-" + file + ".bin");
			}
		}
		createFile("included/keep.txt");

		Map<String, Path> matches = scan(Set.of("/**", "!/generated/**"));

		assertEquals(Set.of("/included/keep.txt"), matches.keySet());
	}

	private Map<String, Path> scan(Set<String> rules) {
		var scanner = new FileTreeScanner(rules, Set.of(rootDir));
		scanner.scan();
		return scanner.getMatchedPaths();
	}

	private void createFile(String relativePath) throws IOException {
		Path file = rootDir.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.createFile(file);
	}
}
