package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.modlauncher.api.ITransformer;
import net.neoforged.neoforgespi.coremod.ICoreMod;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@link ICoreMod} shipped by AutoModpack that forwards the transformers of coremods living in
 * the selected modpack folder, so they run without being copied into the standard {@code mods/}
 * directory.
 *
 * <p>FML collects coremod transformers (its {@code FMLServiceProvider.transformers()} pass) after
 * mod discovery but <em>before</em> the GAME layer is built, scanning only the layers that already
 * exist (BOOT/SERVICE/PLUGIN). A modpack jar reaches at best the GAME layer, so FML never sees its
 * {@code ICoreMod}. AutoModpack itself is on the SERVICE layer and IS scanned - so it instantiates
 * those modpack coremods from the child SERVICE layers {@link EarlyServiceBootstrapper} built for
 * them and returns their transformers here, as if they were AutoModpack's own.
 *
 * <p>This is what lets e.g. Sinytra Connector load fully in place: its own mixins {@code @Shadow}
 * names that only its coremod remaps to the obfuscated runtime, so without this the coremod never
 * runs and Connector's mixins fail to apply.
 */
@SuppressWarnings("unused")
public class AutoModpackCoreMod implements ICoreMod {

    @Override
    public Iterable<? extends ITransformer<?>> getTransformers() {
        List<ITransformer<?>> transformers = new ArrayList<>(EarlyServiceLayer.collectCoremodTransformers());
        // Re-fires the in-place GraphicsBootstrappers on the GAME layer once its launch target
        // loads, repairing the split static state that crashes mods like asynclogger in place.
        transformers.add(new GameGraphicsBootstrapTrigger());
        return transformers;
    }
}
