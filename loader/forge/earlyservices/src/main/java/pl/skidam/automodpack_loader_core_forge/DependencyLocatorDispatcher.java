package pl.skidam.automodpack_loader_core_forge;

import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;

/**
 * The universal outer jar's single {@code IDependencyLocator} provider, forwarding FML 1.19+'s
 * dependency pass to {@code ..._forge_47.LazyModLocator} verbatim. That interface exists only in
 * 1.19+'s forgespi, so ServiceLoader can only ever read this services file - and this class load -
 * on Forge 1.19.2/1.20.1: on 1.18.2 the interface name is unknown (the file is never read), and
 * NeoForge renamed the whole package. No generation guard is needed.
 */
@SuppressWarnings("unused")
public class DependencyLocatorDispatcher extends AbstractJarFileDependencyLocator {
	private static final LazyModLocatorView LAZY = (LazyModLocatorView) instantiate("pl.skidam.automodpack_loader_core_forge_47.LazyModLocator");

	private static Object instantiate(String className) {
		try {
			return Class.forName(className, true, DependencyLocatorDispatcher.class.getClassLoader()).getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Failed to instantiate Forge locator " + className, e);
		}
	}

	@Override
	public String name() {
		return "automodpack";
	}

	@Override
	public void initArguments(Map<String, ?> arguments) {
		LAZY.initArguments(arguments);
	}

	@Override
	public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
		return LAZY.scanMods(loadedMods);
	}
}
