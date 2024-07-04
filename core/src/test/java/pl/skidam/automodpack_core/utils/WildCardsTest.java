package pl.skidam.automodpack_core.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.GlobalVariables.DEBUG;

class WildCardsTest {

    private WildCards wildCards;
    private final Path testFilesDir = Path.of("src/test/resources/testFiles");
    private final String testFilesStr = testFilesDir.toString().replace(File.separator, "/");

    @BeforeEach
    void setUp() {
        DEBUG = true;

        var wildcards = List.of(
                "/" + testFilesStr + "/file.txt",
                "/" + testFilesStr + "/config/config*",
                "/" + testFilesStr + "/config/mod-config.toml",
                "/" + testFilesStr + "/mods/*.jar",
                "!/" + testFilesStr + "/mods/server-*jar",
                "!/" + testFilesStr + "/mods/*19.jar",
                "!/" + testFilesStr + "/shaders/*.txt",
                "/" + testFilesStr + "/thisfiledoesnotexist.txt",
                "/" + testFilesStr + "/shaders/*",
                "!/" + testFilesStr + "/shaders/notashader.zip"
        );
        wildCards = new WildCards(wildcards, List.of(testFilesDir));

        System.out.println();
        System.out.println();
        System.out.println();
        System.out.println();
    }

    @Test
    void shouldReturnCorrectNumberOfMatches() {
        assertEquals(9, wildCards.getWildcardMatches().size());
    }

    // TODO split this to more specific tests
    @Test
    void shouldReturnCorrectMatches() {
        var correctResult = List.of(
                testFilesDir.resolve("file.txt"),
                testFilesDir.resolve("config/config.json"),
                testFilesDir.resolve("config/config-mod.json5"),
                testFilesDir.resolve("config/mod-config.toml"),
                testFilesDir.resolve("mods/mod-1.20.jar"),
                testFilesDir.resolve("mods/client-mod-1.20.jar"),
                testFilesDir.resolve("shaders/shader1.zip"),
                testFilesDir.resolve("shaders/shader2.zip"),
                testFilesDir.resolve("shaders/shader3.zip")
        );
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