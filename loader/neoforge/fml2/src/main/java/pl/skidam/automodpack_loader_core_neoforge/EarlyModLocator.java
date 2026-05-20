package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.modlauncher.api.LamdbaExceptionUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;
import net.neoforged.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import pl.skidam.automodpack_loader_core.Preload;
import pl.skidam.automodpack_loader_core_neoforge.mods.ModpackLoader;

@SuppressWarnings("unused")
public class EarlyModLocator extends AbstractJarFileModLocator {
    private static final String EMBEDDED_MOD_PATH = "META-INF/jarjar/automodpack-mod.jar";

    @Override
    public void initArguments(Map<String, ?> arguments) {}

    @Override
    public String name() {
        return "automodpack";
    }

    @Override
    public Stream<Path> scanCandidates() {
        ProgressMeter progress = StartupNotificationManager.prependProgressBar(
            "[Automodpack] Preload",
            0
        );
        new Preload();
        progress.complete();

        return Stream.concat(
            Stream.of(getEmbeddedModPath()),
            ModpackLoader.modsToLoad.stream()
        ).distinct();
    }

    private Path getEmbeddedModPath() {
        return LamdbaExceptionUtils.uncheck(() -> {
            Path extractedDir = Files.createTempDirectory("automodpack-fml2-embedded-");
            Path extractedJar = extractedDir.resolve("automodpack-mod.jar");
            try (InputStream stream = EarlyModLocator.class.getClassLoader().getResourceAsStream(EMBEDDED_MOD_PATH)) {
                if (stream == null) {
                    throw new IllegalStateException("Missing embedded mod resource: " + EMBEDDED_MOD_PATH);
                }
                Files.copy(stream, extractedJar, StandardCopyOption.REPLACE_EXISTING);
            }
            extractedJar.toFile().deleteOnExit();
            extractedDir.toFile().deleteOnExit();
            return extractedJar;
        });
    }
}
