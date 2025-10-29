package pl.skidam.automodpack_core.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance test to verify WildCards optimizations
 */
class WildCardsPerformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void testSmartDirectoryWalking() throws IOException {
        // Create a directory structure with many files
        Path modsDir = tempDir.resolve("mods");
        Path configDir = tempDir.resolve("config");
        Path shadersDir = tempDir.resolve("shaders");
        Path otherDir = tempDir.resolve("other");
        
        Files.createDirectories(modsDir);
        Files.createDirectories(configDir);
        Files.createDirectories(shadersDir);
        Files.createDirectories(otherDir);

        // Create many files in each directory
        for (int i = 0; i < 1000; i++) {
            Files.createFile(modsDir.resolve("mod-" + i + ".jar"));
            Files.createFile(configDir.resolve("config-" + i + ".json"));
            Files.createFile(shadersDir.resolve("shader-" + i + ".zip"));
            Files.createFile(otherDir.resolve("file-" + i + ".txt"));
        }
        
        // Test with rules that only target specific directories
        var wildcards = List.of(
                "/m*s/*.jar",
                "/**/*.json"
        );
        
        WildCards wildCards = new WildCards(wildcards, Set.of(tempDir));
        
        long startTime = System.nanoTime();
        wildCards.match();
        long endTime = System.nanoTime();
        
        long durationMs = (endTime - startTime) / 1_000_000;
        
        System.out.println("Smart directory walking took: " + durationMs + " ms");
        System.out.println("Found " + wildCards.getWildcardMatches().size() + " matches");
        
        // Should find 1000 mods + 1000 configs = 2000 files
        assertEquals(2000, wildCards.getWildcardMatches().size(), "Expected 2000 matches, got " + wildCards.getWildcardMatches().size());

        // This is more of a smoke test than a hard performance requirement
        assertTrue(durationMs < 5000, 
                "Smart directory walking took too long: " + durationMs + " ms");
    }
    
    @Test
    void testCachedFormattedPaths() throws IOException {
        // Create test files
        Path testDir = tempDir.resolve("test");
        Files.createDirectories(testDir);
        
        for (int i = 0; i < 500; i++) {
            Files.createFile(testDir.resolve("file-" + i + ".txt"));
        }
        
        // Rules that will cause multiple pattern matching attempts per file
        var wildcards = List.of(
                "/test/*.txt",
                "!/test/file-1*.txt",
                "!/test/file-2*.txt"
        );
        
        WildCards wildCards = new WildCards(wildcards, Set.of(tempDir));
        
        long startTime = System.nanoTime();
        wildCards.match();
        long endTime = System.nanoTime();
        
        long durationMs = (endTime - startTime) / 1_000_000;
        
        System.out.println("Cached formatted paths took: " + durationMs + " ms");
        System.out.println("Found " + wildCards.getWildcardMatches().size() + " matches");
        
        // Should exclude files matching the blacklist patterns
        assertTrue(wildCards.getWildcardMatches().size() < 500,
                "Expected fewer than 500 matches due to blacklist");
        
        // Caching should make this fast
        assertTrue(durationMs < 2000, 
                "Cached formatted paths took too long: " + durationMs + " ms");
    }
}
