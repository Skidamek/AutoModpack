package pl.skidam.automodpack_core.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.GlobalVariables.DEBUG;

class WildCardsTest {

    @TempDir
    Path testFilesDir;

    private WildCards wildCards;
    private List<Path> correctResult;

    @BeforeEach
    void setUp() throws IOException {
        DEBUG = true;

        // Create test file structure
        createTestFiles();

        var wildcards = List.of(
                "/file.txt",
                "/config/config*",
                "/config/mod-config.toml",
                "/mods/*.jar",
                "!/mods/server-*jar",
                "!/mods/*19.jar",
                "!/shaders/*.txt",
                "/thisfiledoesnotexist.txt",
                "/shaders/*",
                "!/shaders/notashader.zip",
                "/f*/**",
                "/directory/*/*"
        );
        wildCards = new WildCards(wildcards, Set.of(testFilesDir));
        wildCards.match();

        // Define expected results
        correctResult = List.of(
                testFilesDir.resolve("file.txt"),
                testFilesDir.resolve("config/config.json"),
                testFilesDir.resolve("config/config-mod.json5"),
                testFilesDir.resolve("config/mod-config.toml"),
                testFilesDir.resolve("mods/mod-1.20.jar"),
                testFilesDir.resolve("mods/client-mod-1.20.jar"),
                testFilesDir.resolve("shaders/shader1.zip"),
                testFilesDir.resolve("shaders/shader2.zip"),
                testFilesDir.resolve("shaders/shader3.zip"),
                testFilesDir.resolve("foo/file.json"),
                testFilesDir.resolve("foo/bar/file.json"),
                testFilesDir.resolve("foo/boo/file.json"),
                testFilesDir.resolve("foo/boo/file.toml"),
                testFilesDir.resolve("foo/poo/file.json"),
                testFilesDir.resolve("foo/poo/moo/file.json"),
                testFilesDir.resolve("foo/poo/moo/file2.json"),
                testFilesDir.resolve("directory/which/file.json"),
                testFilesDir.resolve("directory/who/file.json")
        );
    }

    private void createTestFiles() throws IOException {
        // Root level files
        Files.createFile(testFilesDir.resolve("file.txt"));

        // Config directory
        Path configDir = testFilesDir.resolve("config");
        Files.createDirectories(configDir);
        Files.createFile(configDir.resolve("config.json"));
        Files.createFile(configDir.resolve("config-mod.json5"));
        Files.createFile(configDir.resolve("mod-config.toml"));
        Files.createFile(configDir.resolve("random-options.txt"));

        // Mods directory
        Path modsDir = testFilesDir.resolve("mods");
        Files.createDirectories(modsDir);
        Files.createFile(modsDir.resolve("mod-1.20.jar"));
        Files.createFile(modsDir.resolve("mod-1.19.jar"));
        Files.createFile(modsDir.resolve("client-mod-1.20.jar"));
        Files.createFile(modsDir.resolve("client-mod-1.19.jar"));
        Files.createFile(modsDir.resolve("server-mod-1.20.jar"));
        Files.createFile(modsDir.resolve("server-mod-1.19.jar"));
        Files.createFile(modsDir.resolve("mod"));

        // Mods subdirectory
        Path modsRandomDir = modsDir.resolve("random directory");
        Files.createDirectories(modsRandomDir);
        Files.createFile(modsRandomDir.resolve("mod-1.19.jar"));
        Files.createFile(modsRandomDir.resolve("client-mod-1.19.jar"));
        Files.createFile(modsRandomDir.resolve("client-mod-1.20.jar"));
        Files.createFile(modsRandomDir.resolve("mod"));
        Files.createFile(modsRandomDir.resolve("random-config.yaml"));

        // Shaders directory
        Path shadersDir = testFilesDir.resolve("shaders");
        Files.createDirectories(shadersDir);
        Files.createFile(shadersDir.resolve("shader1.zip"));
        Files.createFile(shadersDir.resolve("shader2.zip"));
        Files.createFile(shadersDir.resolve("shader3.zip"));
        Files.createFile(shadersDir.resolve("notashader.zip"));
        Files.createFile(shadersDir.resolve("shaderconfig.txt"));

        // Foo directory with nested structure
        Path fooDir = testFilesDir.resolve("foo");
        Files.createDirectories(fooDir);
        Files.createFile(fooDir.resolve("file.json"));

        Path fooBarDir = fooDir.resolve("bar");
        Files.createDirectories(fooBarDir);
        Files.createFile(fooBarDir.resolve("file.json"));

        Path fooBooDir = fooDir.resolve("boo");
        Files.createDirectories(fooBooDir);
        Files.createFile(fooBooDir.resolve("file.json"));
        Files.createFile(fooBooDir.resolve("file.toml"));

        Path fooPooDir = fooDir.resolve("poo");
        Files.createDirectories(fooPooDir);
        Files.createFile(fooPooDir.resolve("file.json"));

        Path fooPooMooDir = fooPooDir.resolve("moo");
        Files.createDirectories(fooPooMooDir);
        Files.createFile(fooPooMooDir.resolve("file.json"));
        Files.createFile(fooPooMooDir.resolve("file2.json"));

        // Directory with nested structure
        Path directoryDir = testFilesDir.resolve("directory");
        Files.createDirectories(directoryDir);
        Files.createFile(directoryDir.resolve("file.json"));

        Path directoryWhichDir = directoryDir.resolve("which");
        Files.createDirectories(directoryWhichDir);
        Files.createFile(directoryWhichDir.resolve("file.json"));

        Path directoryWhoDir = directoryDir.resolve("who");
        Files.createDirectories(directoryWhoDir);
        Files.createFile(directoryWhoDir.resolve("file.json"));
    }

    @Test
    void shouldReturnCorrectNumberOfMatches() {
        assertEquals(correctResult.size(), wildCards.getWildcardMatches().size());
    }

    @Test
    void shouldReturnCorrectMatches() {
        boolean correct = true;

        for (var item : wildCards.getWildcardMatches().values()) {
            System.out.println(item);
            if (!correctResult.contains(item)) {
                correct = false;
                System.out.println("INCORRECT");
                break;
            }
        }

        System.out.println();

        for (var item : correctResult) {
            System.out.println(item);
            if (!wildCards.getWildcardMatches().containsValue(item)) {
                System.out.println("INCORRECT");
                correct = false;
                break;
            }
        }

        assertTrue(correct);
        assertEquals(correctResult.size(), wildCards.getWildcardMatches().size());
    }

    @Test
    void shouldExcludeBlacklistedFiles() {
        assertFalse(wildCards.getWildcardMatches().containsValue(testFilesDir.resolve("mods/server-mod-1.20.jar")));
        assertFalse(wildCards.getWildcardMatches().containsValue(testFilesDir.resolve("mods/server-mod-1.19.jar")));
        assertFalse(wildCards.getWildcardMatches().containsValue(testFilesDir.resolve("mods/client-mod-1.19.jar")));
        assertFalse(wildCards.getWildcardMatches().containsValue(testFilesDir.resolve("mods/mod-1.19.jar")));
        assertFalse(wildCards.getWildcardMatches().containsValue(testFilesDir.resolve("mods/mod")));
        assertFalse(wildCards.getWildcardMatches().containsValue(testFilesDir.resolve("shaders/notashader.zip")));
        assertFalse(wildCards.getWildcardMatches().containsValue(testFilesDir.resolve("config/random-options.txt")));
    }

    @Test
    void shouldNotIncludeNonexistentFiles() {
        assertFalse(wildCards.getWildcardMatches().containsValue(testFilesDir.resolve("thisfiledoesnotexist.txt")));
    }

    @Test
    void shouldNotIncludeDirectories() {
        assertFalse(wildCards.getWildcardMatches().containsValue(testFilesDir.resolve("shaders/")));
        assertFalse(wildCards.getWildcardMatches().containsValue(testFilesDir.resolve("shaders/texture.txt")));
    }
}