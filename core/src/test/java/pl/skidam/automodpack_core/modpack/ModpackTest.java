package pl.skidam.automodpack_core.modpack;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.GlobalVariables.DEBUG;

class ModpackTest {

    private final Path testFilesDir = Path.of("src/test/resources/testFiles");
    private final String testFilesStr = testFilesDir.toString().replace(File.separator, "/");

    @Test
    void modpackTest() {
        DEBUG = true;

        var editable = List.of(
                "/" + testFilesStr + "/file.txt",
                "/" + testFilesStr + "/config/*",
                "!/" + testFilesStr + "/config/config-mod.json5"
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

        ModpackContent content = new ModpackContent("TestPack", null, testFilesDir, new ArrayList<>(), new ArrayList<>(editable), new ModpackExecutor().getExecutor());
        content.create();

        boolean correct = true;

        System.out.println();
        System.out.println();
        System.out.println();

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