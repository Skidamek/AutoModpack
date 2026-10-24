package pl.skidam.automodpack_loader_core_forge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;

import pl.skidam.automodpack_core.loader.ImplStore;

/**
 * Surfaces the one jar's impl for this forge target as a mod file. On Forge 1.18.2 it also replays
 * the early-service jars' dependency locators (see {@link EarlyServiceLayer}), because that
 * generation's dependency pass is this same hook. On 1.19+ the hosted locators must run after the
 * loader's own ones, so {@link HostedDependencyLocator} replays them and this pass contributes only
 * the embedded impl - which has to stay early so Forge's JarInJar locator still expands its nested
 * MixinExtras.
 *
 * <p>
 * Lives here because it is version-agnostic; the one forgespi-seamed call, {@code createMod} -
 * {@code Optional<IModFile>} on 1.18.2, {@code IModLocator.ModFileOrException} on 1.19+ - stays in
 * the per-version subclasses via {@link #embeddedMod(Path)}, since this module compiles against a
 * single forgespi generation for both.
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
		addEmbeddedMod(list);
		EarlyServiceLayer.runHostedDependencyLocators(loadedMods, list);
		return list;
	}

	/** Adds this target's impl mod file; on 1.19+ its nested jars are expanded by the loader's own JarInJar pass. */
	protected final void addEmbeddedMod(List<IModFile> out) {
		try {
			out.add(embeddedMod());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String name() {
		return null;
	}

	@Override
	public void initArguments(Map<String, ?> arguments) {}

	/** Selects this target's impl into the instance's impl cache and mounts it - {@code createMod} works on plain file paths. */
	private IModFile embeddedMod() throws Exception {
		return embeddedMod(ImplStore.select(LazyModLocatorBase.class, "forge"));
	}

	/** {@code createMod(modPath)} unwrapped - the return type is this class's only per-version seam. */
	protected abstract IModFile embeddedMod(Path modPath);
}
