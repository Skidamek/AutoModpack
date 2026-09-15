package pl.skidam.automodpack_loader_core_forge;

import java.util.List;
import java.util.Map;

import net.minecraftforge.forgespi.locating.IModFile;

/**
 * The dispatcher's compiled view of a generation's lazy mod locator ({@code LazyModLocator} of
 * {@code ..._forge_40} or {@code ..._forge_47}) - the locator that surfaces the nested
 * automodpack-mod.jar and replays early-service dependency locators. Every type in these signatures
 * exists on both Forge generations, so implementers load wherever either generation runs.
 */
public interface LazyModLocatorView {
	void initArguments(Map<String, ?> arguments);

	List<IModFile> scanMods(Iterable<IModFile> loadedMods);
}
