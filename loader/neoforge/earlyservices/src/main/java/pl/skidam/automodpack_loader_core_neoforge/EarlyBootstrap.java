package pl.skidam.automodpack_loader_core_neoforge;

import net.neoforged.fml.loading.ProgramArgs;
import net.neoforged.neoforgespi.EarlyBootstrapper;

/**
 * FML 12.0.10+ early hook (every env, including headless). The class is only ServiceLoaded when
 * {@link EarlyBootstrapper} exists, so it is never linked on older NeoForge. Same work as
 * {@link EarlyServiceBootstrapper}; {@link EarlyServiceBootstrapper#run(String[])} is one-shot.
 */
public class EarlyBootstrap implements EarlyBootstrapper {

	@Override
	public String name() {
		return "automodpack";
	}

	@Override
	public void bootstrap(ProgramArgs arguments) {
		EarlyServiceBootstrapper.run(arguments.getArguments());
	}
}
