package pl.skidam.automodpack_loader_core_forge_47;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.forgespi.locating.IModLocator;

import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_loader_core_forge.EarlyModLocatorBase;
import pl.skidam.automodpack_loader_core_forge.EarlyModLocatorView;
import pl.skidam.automodpack_loader_core_forge.EarlyServiceLayer;

@SuppressWarnings("unused")
public class EarlyModLocator extends EarlyModLocatorBase implements EarlyModLocatorView {

	public EarlyModLocator() {
		super(GenerationProbes.FORGE_FML47);
	}

	@Override
	public List<IModLocator.ModFileOrException> scanMods() {
		// Second line of defense behind ModLocatorDispatcher's generation pick; only 1.19+ may act.
		if (!GenerationProbes.FORGE_FML47) return List.of();

		List<IModLocator.ModFileOrException> results = new ArrayList<>(super.scanMods());
		for (var jar : EarlyServiceLayer.registeredJars()) {
			List<Object> extra = new ArrayList<>();
			EarlyServiceLayer.runCandidateLocators(jar, extra);
			for (Object o : extra) {
				results.add((IModLocator.ModFileOrException) o);
			}
		}
		return results;
	}
}
