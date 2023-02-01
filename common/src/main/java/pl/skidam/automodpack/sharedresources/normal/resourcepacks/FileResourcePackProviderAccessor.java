package pl.skidam.automodpack.sharedresources.normal.resourcepacks;

import net.minecraft.resource.FileResourcePackProvider;
import net.minecraft.resource.ResourceType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Credits to enjarai for the original code (https://github.com/enjarai/shared-resources)
 */

@Mixin(FileResourcePackProvider.class)
public interface FileResourcePackProviderAccessor {
    @Accessor("type")
    ResourceType sharedresources$getResourceType();
}