package pl.skidam.automodpack_loader_core_forge;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pl.skidam.automodpack_loader_core.Preload;
import pl.skidam.automodpack_loader_core_forge.mods.ModpackLoader;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

@SuppressWarnings("unused")
public class EarlyModLocator extends AbstractJarFileModLocator {
    public static Logger LOGGER = LogManager.getLogger("AutoModpack/BootStrap");
    @Override
    public void initArguments(Map<String, ?> arguments) { }
    @Override
    public String name() {
        return "automodpack_bootstrap";
    }


    @Override
    public Stream<Path> scanCandidates() {

        new Preload();

        ModpackLoader.modsToAdd.forEach(path -> LOGGER.info("Adding mod: {}", path.getFileName()));

        return ModpackLoader.modsToAdd.stream();
    }
}