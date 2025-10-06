package pl.skidam.automodpack_loader_core_neoforge;

import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.*;
import pl.skidam.automodpack_loader_core.Preload;
import pl.skidam.automodpack_loader_core_neoforge.mods.ModpackLoader;

import java.nio.file.Path;

@SuppressWarnings("unused")
public class EarlyModLocator implements IModFileCandidateLocator {

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {

        ProgressMeter progress = StartupNotificationManager.prependProgressBar("[Automodpack] Preload", 0);
        new Preload();
        progress.complete();

        for (Path path : ModpackLoader.modsToLoad) {
            pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
            try {
                JarContents jarContents = JarContents.ofPath(path);
                pipeline.readModFile(jarContents, ModFileDiscoveryAttributes.DEFAULT);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public int getPriority() {
        return IModFileCandidateLocator.HIGHEST_SYSTEM_PRIORITY;
    }
}