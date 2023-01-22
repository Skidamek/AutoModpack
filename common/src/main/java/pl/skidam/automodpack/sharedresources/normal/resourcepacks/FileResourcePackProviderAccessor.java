package pl.skidam.automodpack.sharedresources.normal.resourcepacks;

import net.minecraft.resource.FileResourcePackProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.io.File;

/**
 * Credits to enjarai for the original code (https://github.com/enjarai/shared-resources)
 */

@SuppressWarnings("unused")
@Mixin(FileResourcePackProvider.class)
public interface FileResourcePackProviderAccessor {
    @Accessor("packsFolder")
    File getPacksFolder();

    @Accessor("packsFolder")
    @Mutable
    void setPacksFolder(File packsFolder);
}
