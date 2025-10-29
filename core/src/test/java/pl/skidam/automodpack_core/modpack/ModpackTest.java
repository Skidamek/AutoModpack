package pl.skidam.automodpack_core.modpack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.config.Jsons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.GlobalVariables.DEBUG;

class ModpackTest {

    @TempDir
    Path testFilesDir;

    @BeforeEach
    void setUp() throws IOException {
        DEBUG = true;
        createTestFiles();
    }

    private void createTestFiles() throws IOException {
        // Root level files
        Files.writeString(testFilesDir.resolve("file.txt"), "a");

        // Config directory
        Path configDir = testFilesDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), "a");
        Files.writeString(configDir.resolve("config-mod.json5"), "a");
        Files.writeString(configDir.resolve("mod-config.toml"), "a");
        Files.writeString(configDir.resolve("random-options.txt"), "a");

        // Mods directory
        Path modsDir = testFilesDir.resolve("mods");
        Files.createDirectories(modsDir);
        Files.writeString(modsDir.resolve("mod-1.20.jar"), "a");
        Files.writeString(modsDir.resolve("mod-1.19.jar"), "a");
        Files.writeString(modsDir.resolve("client-mod-1.20.jar"), "a");
        Files.writeString(modsDir.resolve("client-mod-1.19.jar"), "a");
        Files.writeString(modsDir.resolve("server-mod-1.20.jar"), "a");
        Files.writeString(modsDir.resolve("server-mod-1.19.jar"), "a");
        Files.writeString(modsDir.resolve("mod"), "a");

        // Mods subdirectory
        Path modsRandomDir = modsDir.resolve("random directory");
        Files.createDirectories(modsRandomDir);
        Files.writeString(modsRandomDir.resolve("random-config.yaml"), "a");

        // Shaders directory
        Path shadersDir = testFilesDir.resolve("shaders");
        Files.createDirectories(shadersDir);
        Files.writeString(shadersDir.resolve("shader1.zip"), "a");
        Files.writeString(shadersDir.resolve("shader2.zip"), "a");
        Files.writeString(shadersDir.resolve("shader3.zip"), "a");
        Files.writeString(shadersDir.resolve("notashader.zip"), "a");
        Files.writeString(shadersDir.resolve("shaderconfig.txt"), "a");
    }

    @Test
    void modpackTest() {
        // Use relative paths for editable rules (relative to testFilesDir)
        var editable = List.of(
                "/file.txt",
                "/config/*",
                "!/config/config-mod.json5"
        );

        editable.forEach(System.out::println);

        var correctResults = List.of(
                "ModpackContentItems(file=/shaders/notashader.zip, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/config/config-mod.json5, size=1, type=config, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/mods/random directory/random-config.yaml, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/file.txt, size=1, type=other, editable=true, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/shaders/shader1.zip, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/config/config.json, size=1, type=config, editable=true, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/mods/client-mod-1.19.jar, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/shaders/shader2.zip, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/config/mod-config.toml, size=1, type=config, editable=true, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/shaders/shader3.zip, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/mods/client-mod-1.20.jar, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/shaders/shaderconfig.txt, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/mods/mod, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/config/random-options.txt, size=1, type=config, editable=true, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/mods/mod-1.19.jar, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/mods/mod-1.20.jar, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/mods/server-mod-1.19.jar, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/mods/server-mod-1.20.jar, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)"
        );

        GlobalVariables.serverConfig = new Jsons.ServerConfigFieldsV2();
        GlobalVariables.serverConfig.autoExcludeUnnecessaryFiles = false;

        ModpackContent content = new ModpackContent("TestPack", null, testFilesDir, new ArrayList<>(), new ArrayList<>(editable), new ArrayList<>(), new ModpackExecutor().getExecutor());
        content.create();

        boolean correct = true;

        System.out.println();

        if (content.list.size() != correctResults.size()) {
            System.out.println("Incorrect number of items! Expected " + correctResults.size() + " but got " + content.list.size());
            correct = false;
        }

        for (var item : content.list) {
            if (correctResults.contains(item.toString())) {
                System.out.println("Correct: " + item);
            } else {
                System.out.println("Incorrect: " + item);
                correct = false;
                break;
            }
        }

        assertTrue(correct);
    }

}