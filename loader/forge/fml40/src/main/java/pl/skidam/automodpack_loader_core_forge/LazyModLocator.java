package pl.skidam.automodpack_loader_core_forge;

import com.google.common.collect.ImmutableMap;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.*;

import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.uncheck;

@SuppressWarnings("unused")
public class LazyModLocator extends AbstractJarFileDependencyLocator {
    @Override
    public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
        var list = new ArrayList<IModFile>(1);
        try {
            list.add(getMainMod());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return list;
    }

    @Override
    public String name() {
        return null;
    }

    @Override
    public void initArguments(Map<String, ?> arguments) { }

    // Code based on connector's https://github.com/Sinytra/Connector/blob/0514fec8f189b88c5cec54dc5632fbcee13d56dc/src/main/java/dev/su5ed/sinytra/connector/locator/EmbeddedDependencies.java#L88
    private IModFile getMainMod() throws IOException, URISyntaxException {
        final Path SELF_PATH = uncheck(() -> {
            URL jarLocation = LazyModLocator.class.getProtectionDomain().getCodeSource().getLocation();
            return Path.of(jarLocation.toURI());
        });
        final String depName = "META-INF/jarjar/automodpack-mod.jar";

        final Path pathInModFile = SELF_PATH.resolve(depName);
        final URI filePathUri = new URI("jij:" + pathInModFile.toAbsolutePath().toUri().getRawSchemeSpecificPart()).normalize();
        final Map<String, ?> outerFsArgs = ImmutableMap.of("packagePath", pathInModFile);
        final FileSystem zipFS = FileSystems.newFileSystem(filePathUri, outerFsArgs);
        final Path modPath = zipFS.getPath("/");

        var mod = createMod(modPath); // requires securejarhandler 1.0.8 or higher so forge 40.2.3 and up
        return mod.orElseThrow();
    }
}
