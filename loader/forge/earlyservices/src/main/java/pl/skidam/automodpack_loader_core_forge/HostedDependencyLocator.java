package pl.skidam.automodpack_loader_core_forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;

/**
 * The late half of the hosted dependency pass: replays the early-service jars' dependency locators
 * after every native one. {@link EarlyServiceLayer#appendHostedDependencyLocator()} adds it to
 * FML's ordered list during the candidate pass because a hosted jar cannot reorder that list itself.
 * Connector does exactly that natively, and its Fabric resolution depends on the jar-in-jar modules
 * Forge's JarInJar locator contributes - modules a locator only sees if it runs after JarInJar.
 *
 * <p>
 * Never ServiceLoaded; only ever instantiated by the append above, on Forge 1.19+.
 */
public class HostedDependencyLocator extends AbstractJarFileDependencyLocator {
	public static final String NAME = "automodpack_hosted";

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public void initArguments(Map<String, ?> arguments) {}

	@Override
	public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
		var out = new ArrayList<IModFile>();
		EarlyServiceLayer.runHostedDependencyLocators(loadedMods, out);
		return out;
	}
}
