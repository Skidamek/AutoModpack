package pl.skidam.automodpack.sharedresources.compat.iris.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static pl.skidam.automodpack.AutoModpack.selectedModpackDir;

/**
 * Credits to enjarai for the original code (https://github.com/enjarai/shared-resources)
 */

@Pseudo
@Mixin(targets = "net.coderbot.iris.shaderpack.discovery.ShaderpackDirectoryManager", priority = 2137)
public abstract class ShaderpackDirectoryManagerMixin {
    @Redirect(
            method = "enumerate",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/nio/file/Files;list(Ljava/nio/file/Path;)Ljava/util/stream/Stream;"
            ),
            remap = false
    )
    private Stream<Path> shared_resources$injectShaderpacks(Path originalPath) throws IOException {
        Stream<Path> original = Files.list(originalPath);

        if (selectedModpackDir == null) return original;
        File newPath = new File(selectedModpackDir + File.separator + "shaderpacks");

        if (!newPath.exists()) return original;

        try {
            Stream<Path> extraPaths = Files.list(newPath.toPath());
            return Stream.concat(original, extraPaths);
        } catch (IOException e) {
            return original;
        }
    }
}
