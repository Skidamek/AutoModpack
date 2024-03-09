package pl.skidam.automodpack_loader_core_forge.mods;

import com.google.common.collect.ImmutableMap;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.minecraftforge.forgespi.locating.IModFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_loader_core.Preload;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.uncheck;

@SuppressWarnings("unused")
public class ModLocator extends AbstractJarFileModLocator {
    public static Logger LOGGER = LogManager.getLogger("AutoModpack/ModLocator");
    @Override
    public void initArguments(Map<String, ?> arguments) { }

    @Override
    public String name() {
        return "automodpack_locator";
    }

    @Override
    public Stream<Path> scanCandidates() {
        return Stream.empty();
    }

    @Override
    public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
        List<IModFile> loadedModsList = new ArrayList<>();
        loadedMods.forEach(loadedModsList::add);

        IModFile automodpackMod = uncheck(this::getMainMod);
        loadedModsList.add(automodpackMod);
        new LoadedMods(loadedModsList);

        new Preload();

        List<Optional<IModFile>> optionalMods = scanOurCandidates()
                .map(this::createMod)
                .toList();
        List<IModFile> mods = new ArrayList<>(optionalMods.stream()
                .filter(Objects::nonNull) // It shouldn't ever happen, but better to be sure
                .filter(Optional::isPresent) // Filter non mod files
                .map(Optional::get) // Get
                .toList());

        if (!mods.isEmpty()) {
            LOGGER.info("Loading mods from modpack {}:", GlobalVariables.clientConfig.selectedModpack);

            for (IModFile mod : mods) {
                LOGGER.info(" - {}", mod.getFileName());
            }
        }

        //TODO run preload

        return mods;
    };

    private Stream<Path> scanOurCandidates() {
        var modpackPath = SetupMods.modpackPath;
        var modsToAdd = SetupMods.modsToAdd;
        var modsToRemove = SetupMods.modsToRemove;
        if (modpackPath != null || !modsToAdd.isEmpty() || !modsToRemove.isEmpty()) {

            Path modpackModsPath = Path.of(modpackPath + File.separator + "mods");
            List<Path> modpackMods = new ArrayList<>();

            if (Files.exists(modpackModsPath) && Files.isDirectory(modpackModsPath)) {
                LOGGER.debug("Loading mods from {}", modpackModsPath.toAbsolutePath());
                modpackMods = uncheck(() -> Files.list(modpackModsPath)).toList();
            }

            List<Path> allMods = new ArrayList<>();
            allMods.addAll(modpackMods);
            allMods.addAll(modsToAdd);;
            allMods.removeAll(modsToRemove);

            return allMods.stream();
        }

        return Stream.empty();
    }

    // Code based on connector's https://github.com/Sinytra/Connector/blob/40e1feb4feea94fba25f126d5ffd56f1379b9e7f/src/main/java/dev/su5ed/sinytra/connector/locator/EmbeddedDependencies.java#L82
    private IModFile getMainMod() throws IOException, URISyntaxException {
        Path SELF_PATH = uncheck(() -> {
            URL jarLocation = ModLocator.class.getProtectionDomain().getCodeSource().getLocation();
            return Path.of(jarLocation.toURI());
        });

        String modFileName = "automodpack-mod.jar";
        Path pathInModFile = SELF_PATH.resolve(modFileName);

        URI filePathUri = new URI("jij:" + pathInModFile.toAbsolutePath().toUri().getRawSchemeSpecificPart()).normalize();
        Map<String, ?> outerFsArgs = ImmutableMap.of("packagePath", pathInModFile);
        FileSystem zipFS = FileSystems.newFileSystem(filePathUri, outerFsArgs);
        Path modPath = zipFS.getPath("/");

        Optional<IModFile> modFile = createMod(modPath);
        return modFile.orElseThrow(RuntimeException::new);
    }
}