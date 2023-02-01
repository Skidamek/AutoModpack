package pl.skidam.automodpack.sharedresources.normal.resourcepacks;

import net.minecraft.resource.FileResourcePackProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import pl.skidam.automodpack.utils.FileResourcepackProviderProxy;

import java.nio.file.Path;

/**
 * Credits to enjarai for the original code (https://github.com/enjarai/shared-resources)
 */

@Mixin(FileResourcePackProvider.class)
public class FileResourcePackProviderMixin implements FileResourcepackProviderProxy {
    @Mutable @Shadow @Final private Path packsDir;

    @Override
    public void sharedresources$setPacksFolder(Path folder) {
        this.packsDir = folder;
    }

    @Override
    public Path sharedresources$getPacksFolder() {
        return packsDir;
    }
}