package pl.skidam.automodpack_core.modpack;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.GlobalVariables.DEBUG;

class ModpackTest {

    private final Path testFilesDir = Path.of("src/test/resources/testFiles");

    @Test
    void modpackTest() {
        DEBUG = true;

        var wildcards = List.of(
                testFilesDir + "/file.txt",
                testFilesDir + "/config/config*",
                "/" + testFilesDir + "/config/mod-config.toml",
                "/" + testFilesDir + "/mods/*.jar",
                "!/" + testFilesDir + "/mods/server-*jar",
                "!" + testFilesDir + "/mods/*19.jar",
                "!" + testFilesDir + "/shaders/*.txt",
                testFilesDir + "/thisfiledoesnotexist.txt",
                testFilesDir + "/shaders/",
                "!/" + testFilesDir + "/shaders/notashader.zip"
        );

        var editable = List.of(
                testFilesDir + "/file.txt",
                testFilesDir + "/config/*",
                "!" + testFilesDir + "/config/config-mod.json5"
        );

        var correctResoults = List.of(
                "ModpackContentItems(file=/shaders/shader3.zip, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/mods/client-mod-1.20.jar, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/mods/mod-1.20.jar, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/shaders/shader2.zip, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/config/config-mod.json5, size=1, type=config, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/shaders/shader1.zip, size=1, type=other, editable=false, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/config/mod-config.toml, size=1, type=config, editable=true, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/file.txt, size=1, type=other, editable=true, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)",
                "ModpackContentItems(file=/config/config.json, size=1, type=config, editable=true, sha1=86f7e437faa5a7fce15d1ddcb9eaeaea377667b8, murmur=null)"
        );

        ModpackContent content = new ModpackContent("TestPack", testFilesDir, null, new ArrayList<>(wildcards), new ArrayList<>(editable), new Modpack().CREATION_EXECUTOR);
        content.create();

        boolean correct = false;

        System.out.println();
        System.out.println();
        System.out.println();

        for (var item : content.list) {
            System.out.println(item);
            if (correctResoults.contains(item.toString())) {
                correct = true;
            } else {
                correct = false;
                break;
            }
        }

        assertTrue(correct);
    }

}