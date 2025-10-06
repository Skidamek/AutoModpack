package pl.skidam.automodpack_loader_core_neoforge;

import net.neoforged.fml.classloading.SecureJar;
import net.neoforged.fml.loading.moddiscovery.locators.JarInJarDependencyLocator;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.locating.*;

import java.nio.file.Path;
import java.util.List;

@SuppressWarnings("unused")
public class LazyModLocator implements IDependencyLocator {

    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        try {
            SecureJar secureJar = SecureJar.from(Path.of(LazyModLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
            IModFile modFile = IModFile.create(secureJar, JarModsDotTomlModFileReader::manifestParser);
            new JarInJarDependencyLocator().scanMods(List.of(modFile), pipeline);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getPriority() {
        return IModFileCandidateLocator.LOWEST_SYSTEM_PRIORITY;
    }

}
