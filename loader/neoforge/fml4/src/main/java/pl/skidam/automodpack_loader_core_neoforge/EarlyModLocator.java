package pl.skidam.automodpack_loader_core_neoforge;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pl.skidam.automodpack_loader_core.Preload;
import pl.skidam.automodpack_loader_core_neoforge.mods.ModpackLoader;

import java.nio.file.Path;

@SuppressWarnings("unused")
public class EarlyModLocator implements IModFileCandidateLocator {
    public static Logger LOGGER = LogManager.getLogger("AutoModpack/BootStrap");

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        LOGGER.info("Finding automodpack early candidates");

        new Preload();

        for (Path path : ModpackLoader.modsToAdd) {
            pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
        }
    }

    @Override
    public int getPriority() {
        return IModFileCandidateLocator.HIGHEST_SYSTEM_PRIORITY;
    }
}