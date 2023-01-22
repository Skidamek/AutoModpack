package pl.skidam.automodpack.sharedresources.compat.iris.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.skidam.automodpack.sharedresources.compat.iris.IrisMixinHooks;

import java.io.File;
import java.nio.file.Path;

import static pl.skidam.automodpack.AutoModpack.selectedModpackDir;

/**
 * Credits to enjarai for the original code (https://github.com/enjarai/shared-resources)
 */

@Pseudo
@Mixin(targets = "net.coderbot.iris.Iris", priority = 2137)
public abstract class IrisMixin {
    @Inject(
            method = "getShaderpacksDirectory()Ljava/nio/file/Path;",
            at = @At(value = "HEAD"),
            cancellable = true
    )
    private static void shared_resources$injectShaderpacksDirectory(CallbackInfoReturnable<Path> ci) {

        if (IrisMixinHooks.fixShaderpackFolders > 0) {
            IrisMixinHooks.fixShaderpackFolders--;

            if (selectedModpackDir == null) return;
            File newPath = new File(selectedModpackDir + File.separator + "shaderpacks");

            if (newPath.exists()) {
                ci.setReturnValue(newPath.toPath());
            }
        }
    }

    @Inject(
            method = "loadExternalShaderpack(Ljava/lang/String;)Z",
            at = @At(value = "HEAD")
    )
    private static void shared_resources$fixShaderpackDirectory(String name, CallbackInfoReturnable<Boolean> ci) {

        if (selectedModpackDir == null) return;
        File newPath = new File(selectedModpackDir + File.separator + "shaderpacks");

        if (!newPath.exists()) return;

        if (newPath.toPath().resolve(name).toFile().exists()) {
            IrisMixinHooks.fixShaderpackFolders += 2;
        }
    }
}
