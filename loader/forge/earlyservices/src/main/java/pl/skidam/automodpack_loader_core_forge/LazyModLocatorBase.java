package pl.skidam.automodpack_loader_core_forge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;

import pl.skidam.automodpack_core.loader.EarlyLaunchEnvironment;
import pl.skidam.automodpack_core.loader.ImplStore;

/**
 * Surfaces the one jar's impl for this forge target as a mod file, then replays the early-service
 * jars' dependency locators (see {@link EarlyServiceLayer}). Lives here because it is
 * version-agnostic; the one forgespi-seamed call, {@code createMod} - {@code Optional<IModFile>} on
 * 1.18.2, {@code IModLocator.ModFileOrException} on 1.19+ - stays in the per-version subclasses via
 * {@link #embeddedMod(Path)}, since this module compiles against a single forgespi generation for
 * both.
 *
 * <p>
 * This class is not ServiceLoaded: the universal outer jar registers only the generation-agnostic
 * dispatchers ({@link ModLocatorDispatcher}, {@link DependencyLocatorDispatcher}), which instantiate
 * the subclass matching {@code GenerationProbes} and reach it through {@link LazyModLocatorView}.
 */
@SuppressWarnings("unused")
public abstract class LazyModLocatorBase extends AbstractJarFileDependencyLocator {
	@Override
	public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
		var list = new ArrayList<IModFile>(1);
		try {
			list.add(embeddedMod());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		for (Path jar : EarlyServiceLayer.registeredJars()) {
			EarlyServiceLayer.runDependencyLocators(jar, loadedMods, list);
		}

		return list;
	}

	@Override
	public String name() {
		return null;
	}

	@Override
	public void initArguments(Map<String, ?> arguments) {}

	/** Selects this target's impl into the instance's impl cache and mounts it - {@code createMod} works on plain file paths. */
	private IModFile embeddedMod() throws Exception {
		String mcVersion = EarlyLaunchEnvironment.MC_VERSION;
		Boolean client = EarlyLaunchEnvironment.IS_CLIENT;
		if (mcVersion == null || client == null) throw new IllegalStateException("AutoModpack cannot tell its forge target before mounting the impl jar");
		Path implJar = ImplStore.select(LazyModLocatorBase.class, "forge", mcVersion, client);
		return embeddedMod(implJar);
	}

	/** {@code createMod(modPath)} unwrapped - the return type is this class's only per-version seam. */
	protected abstract IModFile embeddedMod(Path modPath);
}
