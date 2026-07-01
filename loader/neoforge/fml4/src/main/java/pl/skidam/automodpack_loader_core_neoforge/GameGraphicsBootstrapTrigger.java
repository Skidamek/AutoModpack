package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;

/**
 * A no-op class transformer over the launch target ({@code net.minecraft.client.main.Main} /
 * {@code net.minecraft.server.Main}) used purely for its <em>timing</em>: it is invoked while that
 * class is loaded by the GAME classloader - after the GAME layer exists but before its {@code main}
 * runs (and before any mod's classes load during construction) - which is exactly when the GAME
 * classloader must be bridged to the in-place early-service child layers
 * ({@link EarlyServiceLayer#bridgeEarlyServicesToGameLayer()}).
 *
 * <p>The bytecode is returned unchanged. This trigger is registered by {@link AutoModpackCoreMod} -
 * which FML scans because AutoModpack is on the SERVICE layer - so it runs even when the modpack
 * ships no coremod of its own.
 */
public class GameGraphicsBootstrapTrigger implements ITransformer<ClassNode> {

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        EarlyServiceLayer.bridgeEarlyServicesToGameLayer();
        return input;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        return Set.of(
                Target.targetClass("net.minecraft.client.main.Main"),
                Target.targetClass("net.minecraft.server.Main"));
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }
}
