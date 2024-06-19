package pl.skidam.automodpack_loader_core_neoforge;

import com.google.common.collect.ImmutableMap;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.*;
import pl.skidam.automodpack_loader_core_neoforge.mods.SetupMods;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;

import static cpw.mods.modlauncher.api.LambdaExceptionUtils.uncheck;

@SuppressWarnings("unused")
public class LazyModLocator implements IModFileCandidateLocator {

    // when we add option to force disable mods from remote server then this might be needed
    public void removeMods() {
        // remove mods
        var modsToRemove = SetupMods.modsToRemove;
        // TODO implement this
    }

    // TODO i dont think we need this, since we are lunching on AbstractJarFileModLocator
    //  meaning that we are already before jij so if we use normal jij we should get the same result (even with update, if path and name wont change... which shouldn't)
    //  without that much mess...
    // We don't want to use regular JiJ mechanic because we need to get current mod metadata for update check and that wouldn't guarantee that it's already loaded to loader.
    private Path getMainMod() throws IOException, URISyntaxException {
        final Path SELF_PATH = uncheck(() -> {
            URL jarLocation = LazyModLocator.class.getProtectionDomain().getCodeSource().getLocation();
            return Path.of(jarLocation.toURI());
        });

        final String depName = "automodpack-mod.jar";

        final Path pathInModFile = SELF_PATH.resolve(depName);
        final URI filePathUri = new URI("jij:" + pathInModFile.toAbsolutePath().toUri().getRawSchemeSpecificPart()).normalize();
        final Map<String, ?> outerFsArgs = ImmutableMap.of("packagePath", pathInModFile);
        final FileSystem zipFS = FileSystems.newFileSystem(filePathUri, outerFsArgs);

        return zipFS.getPath("/");
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        removeMods();

        try {
            Path modPath = getMainMod();
            pipeline.addPath(modPath, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.ERROR);
        } catch (IOException | URISyntaxException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int getPriority() {
        return IModFileCandidateLocator.LOWEST_SYSTEM_PRIORITY;
    }
}
