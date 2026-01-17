package pl.skidam.automodpack_core.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ComplexFileTreeScannerTest {

    @TempDir
    Path rootDir;

    private FileTreeScanner fileTreeScanner;
    private Set<Path> expectedMatches;

    @BeforeEach
    void setUp() throws IOException {
        expectedMatches = new HashSet<>();
        createComplexFileStructure();
        var rules = Set.of(
                // 1. Recursive Middle
                "/app/**/logs/target.log",

                // 2. Character Ranges
                "/builds/v[0-9].[0-9]/artifact.jar",

                // 3. Grouping
                "/env/{prod,dev}/config.{yaml,properties}",

                // 4. Question Mark
                "/data/data-???.bin",

                // 5. Deep Recursive + Extension Exclusion
                "/deep/structure/**/*.txt",
                "!/deep/structure/**/ignore.txt",

                // 6. Complex Folder Name Matching
                "/special/folder-[a-z]*/item.dat",

                // 7. Root wildcard exclusion
                "!/app/**/secret.log",

                // 8. Double recursive
                "/redundant/**/**/file.log",

                // 9. Intervaled recursion
                "/noise/**/dream/wicked/**/games/*.dat",

                // 10. Escaped Characters (Literal Brackets)
                // We want to match "crazy[name].txt".
                // Glob syntax requires escaping brackets: "crazy\[name\].txt"
                // Java String requires escaping backslashes: "crazy\\[name\\].txt"
                "/escaped/crazy\\[name\\].txt"
        );

        fileTreeScanner = new FileTreeScanner(rules, Set.of(rootDir));
        fileTreeScanner.scan();
    }

    @Test
    void verifyComplexPatternCorrectness() {
        Set<Path> actualMatches = new HashSet<>(fileTreeScanner.getMatchedPaths().values());

        assertEquals(expectedMatches.size(), actualMatches.size(),
                "The number of matched files differs from expectation. Check for over-matching or under-matching.");

        for (Path expected : expectedMatches) {
            assertTrue(actualMatches.contains(expected),
                    () -> "Missing expected file: " + rootDir.relativize(expected));
        }
    }

    @Test
    void verifyEscapedPaths() {
        Path escapedTarget = rootDir.resolve("escaped/crazy[name].txt");
        Path similarFile = rootDir.resolve("escaped/crazy_name.txt");

        assertAll("Escaped Character Verification",
                () -> assertTrue(Files.exists(escapedTarget), "Setup error: crazy[name].txt should exist on disk"),
                () -> assertTrue(fileTreeScanner.getMatchedPaths().containsValue(escapedTarget),
                        "Failed to match file with literal brackets using escaped glob pattern"),

                // Ensure we didn't accidentally create a rule that matches everything (like 'crazy?name?')
                () -> assertFalse(fileTreeScanner.getMatchedPaths().containsValue(similarFile),
                        "Escaped pattern matched a file it shouldn't have (loose matching)")
        );
    }

    @Test
    void verifyBlacklistLogic() {
        Path secretLog = rootDir.resolve("app/service/logs/secret.log");
        Path deepIgnore = rootDir.resolve("deep/structure/level1/level2/ignore.txt");

        assertAll("Blacklist Verification",
                () -> assertTrue(Files.exists(secretLog), "Setup error: secret.log should exist"),
                () -> assertFalse(fileTreeScanner.getMatchedPaths().containsValue(secretLog), "Failed to blacklist /app/**/secret.log"),
                () -> assertFalse(fileTreeScanner.getMatchedPaths().containsValue(deepIgnore), "Failed to blacklist deep nested ignore.txt")
        );
    }

    @Test
    void verifyPruningLogicOnNoise() {
        // We ensure that the stress-test noise files (named garbage_*.dat) are NOT matched.
        // We look specifically for the garbage files, as the /noise/ folder now contains valid test targets too.

        long garbageMatches = fileTreeScanner.getMatchedPaths().values().stream()
                .filter(p -> p.getFileName().toString().startsWith("garbage"))
                .count();

        assertEquals(0, garbageMatches, "Performance/Logic Error: Matched garbage files inside 'noise' directory.");
    }

    // ---------------------------------------------------------
    // FILE STRUCTURE GENERATOR
    // ---------------------------------------------------------

    private void createComplexFileStructure() throws IOException {
        // --- 1. Recursive Middle (/app/**/logs/target.log) ---
        createTarget("app/service/logs/target.log");
        createTarget("app/service/ui/logs/target.log");
        createTarget("app/logs/target.log");
        createFile("app/service/logs/secret.log");
        createFile("app/other/garbage.log");

        // --- 2. Character Ranges (/builds/v[0-9].[0-9]/artifact.jar) ---
        createTarget("builds/v1.0/artifact.jar");
        createTarget("builds/v2.5/artifact.jar");
        createFile("builds/v1.a/artifact.jar");
        createFile("builds/vx.0/artifact.jar");
        createFile("builds/v1.0/other.jar");

        // --- 3. Grouping (/env/{prod,dev}/config.{yaml,properties}) ---
        createTarget("env/prod/config.yaml");
        createTarget("env/prod/config.properties");
        createTarget("env/dev/config.yaml");
        createFile("env/test/config.yaml");
        createFile("env/prod/config.json");

        // --- 4. Question Mark (/data/data-???.bin) ---
        createTarget("data/data-001.bin");
        createTarget("data/data-abc.bin");
        createFile("data/data-12.bin");
        createFile("data/data-1234.bin");

        // --- 5. Deep Recursive (/deep/structure/**/*.txt) ---
        createTarget("deep/structure/level1/file.txt");
        createTarget("deep/structure/level1/level2/file.txt");
        createTarget("deep/structure/level1/level2/level3/file.txt");
        createFile("deep/structure/level1/image.png");
        createFile("deep/structure/level1/level2/ignore.txt");

        // --- 6. Complex Folder (/special/folder-[a-z]*/item.dat) ---
        createTarget("special/folder-alpha/item.dat");
        createTarget("special/folder-z/item.dat");
        createFile("special/folder-123/item.dat");

        // --- 7. DOUBLE RECURSIVE STRESS (/redundant/**/**/file.log) ---
        createTarget("redundant/a/b/c/file.log");
        createTarget("redundant/one/file.log");
        createTarget("redundant/file.log");

        // --- 8. INTERLEAVED RECURSION (/noise/**/dream/wicked/**/games/*.dat) ---
        // Should match deep nesting
        createTarget("noise/variation1/dream/wicked/deep/games/match1.dat");
        // Should match shallow nesting (zero directories for **)
        createTarget("noise/dream/wicked/games/match2.dat");
        // Should NOT match (broken chain 'nightmare')
        createFile("noise/variation1/nightmare/wicked/deep/games/fail1.dat");
        // Should NOT match (wrong extension)
        createTarget("noise/dream/wicked/games/match3.dat");
        createFile("noise/dream/wicked/games/fail.txt");

        // --- 9. Noise Generator (Performance Stress) ---
        // Generates 100 folders with 5 files each.
        // Walker must prune these efficiently.
        Path noiseRoot = rootDir.resolve("noise");
        for (int i = 0; i < 100; i++) {
            Path subDir = noiseRoot.resolve("dir_" + i);
            Files.createDirectories(subDir);
            for (int j = 0; j < 5; j++) {
                Files.createFile(subDir.resolve("garbage_" + j + ".dat"));
            }
        }

        // --- 10. Escaped Characters ---
        // Create file physically named: escaped/crazy[name].txt
        createTarget("escaped/crazy[name].txt");

        // Create a look-alike to ensure the glob isn't treating [ ] as a wildcard group
        createFile("escaped/crazy_name.txt");
    }

    private void createTarget(String relativePath) throws IOException {
        Path file = rootDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.createFile(file);
        expectedMatches.add(file);
    }

    private void createFile(String relativePath) throws IOException {
        Path file = rootDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.createFile(file);
        // Do NOT add to expectedMatches
    }
}