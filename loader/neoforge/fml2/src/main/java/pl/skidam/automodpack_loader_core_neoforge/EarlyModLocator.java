package pl.skidam.automodpack_loader_core_neoforge;

import net.neoforged.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import pl.skidam.automodpack_loader_core.Preload;
import pl.skidam.automodpack_loader_core_neoforge.mods.ModpackLoader;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@SuppressWarnings("unused")
public class EarlyModLocator extends AbstractJarFileModLocator {

    @Override
    public void initArguments(Map<String, ?> arguments) { }

    @Override
    public String name() {
        return "automodpack_bootstrap";
    }

    @Override
    public Stream<Path> scanCandidates() {
        ProgressMeter progress = StartupNotificationManager.prependProgressBar("[Automodpack] Preload", 0);
        new Preload();
        progress.complete();

        return ModpackLoader.modsToLoad.stream();
    }
}