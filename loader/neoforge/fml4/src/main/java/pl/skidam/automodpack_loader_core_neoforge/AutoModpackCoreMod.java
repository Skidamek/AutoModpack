package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.modlauncher.api.ITransformer;
import net.neoforged.neoforgespi.coremod.ICoreMod;

/**
 * An {@link ICoreMod} shipped by AutoModpack that forwards the transformers of coremods living in the
 * selected modpack folder, so they run without being copied into the standard {@code mods/} directory.
 * (A modpack jar's own {@code ITransformationService} transformers are forwarded separately, by
 * {@link AutoModpackTransformationService} - a natively ServiceLoader-discovered service, not this
 * FML-collected one.)
 *
 * <p>
 * {@code ICoreMod} is an FML SPI, not a ModLauncher one: FML's own {@code transformers()} pass
 * collects it directly from the layers that already exist (BOOT/SERVICE/PLUGIN) before the GAME layer
 * is built, so a modpack jar's own coremod is never seen there. AutoModpack itself is on the SERVICE
 * layer and IS scanned - so it instantiates those modpack coremods from the child SERVICE layers
 * {@link EarlyServiceBootstrapper} built for them and returns their transformers here, as if they
 * were AutoModpack's own.
 */
@SuppressWarnings("unused")
public class AutoModpackCoreMod implements ICoreMod {

	@Override
	public Iterable<? extends ITransformer<?>> getTransformers() {
		return EarlyServiceLayer.collectForwardedTransformers();
	}
}
