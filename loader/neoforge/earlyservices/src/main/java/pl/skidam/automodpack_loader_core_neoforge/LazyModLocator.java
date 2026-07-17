package pl.skidam.automodpack_loader_core_neoforge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.loading.moddiscovery.locators.JarInJarDependencyLocator;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.locating.*;

import pl.skidam.automodpack_loader_core_neoforge.mods.ModpackLoader;

@SuppressWarnings("unused")
public class LazyModLocator implements IDependencyLocator {

	@Override
	public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
		try {
			JarContents jarContents = JarContents.ofPath(Path.of(LazyModLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
			IModFile modFile = IModFile.create(jarContents, JarModsDotTomlModFileReader::manifestParser);
			new JarInJarDependencyLocator().scanMods(List.of(modFile), pipeline);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		// Replay the dependency locators of early-service jars (e.g. Ixeris) so their real
		// (inner) mod loads in place, from the modpack folder, without being copied to the
		// standard mods directory.
		List<Path> earlyServiceJars = new ArrayList<>();
		for (Path path : ModpackLoader.modsToLoad) {
			if (EarlyServiceLayer.isEarlyServiceJar(path)) earlyServiceJars.add(path);
		}
		EarlyServiceLayer.runDependencyLocators(earlyServiceJars, loadedMods, pipeline);
	}

	@Override
	public int getPriority() {
		return IModFileCandidateLocator.LOWEST_SYSTEM_PRIORITY;
	}

}
