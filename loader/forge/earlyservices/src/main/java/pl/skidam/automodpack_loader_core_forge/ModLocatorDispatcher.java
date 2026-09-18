package pl.skidam.automodpack_loader_core_forge;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;

import pl.skidam.automodpack_core.loader.GenerationProbes;

/**
 * The universal outer jar's single {@code IModLocator} provider: the only class in that services
 * file, so ServiceLoader never sees a registration that could fail a provider type-check on any
 * Forge generation. Its hierarchy names only cross-generation-stable types (AbstractJarFileModLocator
 * and IModLocator exist under the same names in 1.18.2's and 1.19+'s forgespi), and it forwards both
 * of FML's discovery passes to the running generation's real locators - picked once via
 * {@link GenerationProbes} and reached through {@link EarlyModLocatorView}/{@link LazyModLocatorView},
 * which those locators implement. The generation classes themselves are never ServiceLoaded.
 *
 * <p>
 * {@code IModLocator.ModFileOrException} (1.19+) shows up only in {@link #scanMods()}'s erased
 * descriptor; the body just forwards the view's list, so 1.18.2 - which calls this same no-arg pass
 * expecting {@code List<IModFile>} - executes it without ever resolving that type. The 1-arg
 * {@link #scanMods(Iterable)} has no counterpart in 1.19+'s IModLocator (there the pass moved to
 * IDependencyLocator, served by {@link DependencyLocatorDispatcher}), so it is a plain override of
 * 1.18.2's interface default and unreachable on 1.19+.
 */
@SuppressWarnings("unused")
public class ModLocatorDispatcher extends AbstractJarFileModLocator {
	private static final String GENERATION_PACKAGE = GenerationProbes.FORGE_FML40 ? "pl.skidam.automodpack_loader_core_forge_40" : "pl.skidam.automodpack_loader_core_forge_47";
	private static final EarlyModLocatorView EARLY = LocatorViews.instantiate(EarlyModLocatorView.class, GENERATION_PACKAGE + ".EarlyModLocator");
	// Only 1.18.2 runs the 1-arg pass, so only it instantiates that generation's lazy locator.
	private static final LazyModLocatorView LAZY_40 = GenerationProbes.FORGE_FML40 ? LocatorViews.instantiate(LazyModLocatorView.class, "pl.skidam.automodpack_loader_core_forge_40.LazyModLocator") : null;

	@Override
	public String name() {
		return "automodpack";
	}

	@Override
	public void initArguments(Map<String, ?> arguments) {
		EARLY.initArguments(arguments);
		if (LAZY_40 != null) LAZY_40.initArguments(arguments);
	}

	@Override
	public Stream<Path> scanCandidates() {
		return EARLY.scanCandidates();
	}

	@Override
	public List<IModLocator.ModFileOrException> scanMods() {
		@SuppressWarnings("unchecked")
		List<IModLocator.ModFileOrException> result = (List<IModLocator.ModFileOrException>) EARLY.scanMods();
		return result;
	}

	public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
		if (LAZY_40 == null) return List.of();
		return LAZY_40.scanMods(loadedMods);
	}
}
