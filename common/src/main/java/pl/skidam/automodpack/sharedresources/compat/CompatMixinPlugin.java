package pl.skidam.automodpack.sharedresources.compat;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import pl.skidam.automodpack.Platform;

import java.util.List;
import java.util.Set;

/**
 * Credits to enjarai for the original code (https://github.com/enjarai/shared-resources)
 * Mixin config plugin for compat, only applies mixins if specific mod is present.
 */

public interface CompatMixinPlugin extends IMixinConfigPlugin {
    Set<String> getRequiredMods();

    @Override
    default void onLoad(String mixinPackage) {

    }

    @Override
    default String getRefMapperConfig() {
        return null;
    }

    @Override
    default boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        for (String mod : getRequiredMods()) return Platform.isModLoaded(mod);

        return true;
    }

    @Override
    default void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

    }

    @Override
    default List<String> getMixins() {
        return null;
    }

    @Override
    default void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }

    @Override
    default void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }
}
