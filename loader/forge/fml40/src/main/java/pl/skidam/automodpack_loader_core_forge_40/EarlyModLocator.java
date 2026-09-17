package pl.skidam.automodpack_loader_core_forge_40;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.forgespi.locating.IModFile;

import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_loader_core_forge.EarlyModLocatorBase;
import pl.skidam.automodpack_loader_core_forge.EarlyModLocatorView;
import pl.skidam.automodpack_loader_core_forge.EarlyServiceLayer;

@SuppressWarnings("unused")
public class EarlyModLocator extends EarlyModLocatorBase implements EarlyModLocatorView {

	public EarlyModLocator() {
		super(GenerationProbes.FORGE_FML40);
	}

	// Forge 1.18.2's IModLocator#scanMods() returns List<IModFile> directly; no ModFileOrException
	// wrapper here (added in a later forgespi version), unlike fml47's override.
	@Override
	public List<IModFile> scanMods() {
		if (!GenerationProbes.FORGE_FML40) return List.of();

		List<IModFile> results = new ArrayList<>(super.scanMods());
		for (var jar : EarlyServiceLayer.registeredJars()) {
			List<Object> extra = new ArrayList<>();
			EarlyServiceLayer.runCandidateLocators(jar, extra);
			for (Object o : extra) {
				results.add((IModFile) o);
			}
		}
		return results;
	}
}
