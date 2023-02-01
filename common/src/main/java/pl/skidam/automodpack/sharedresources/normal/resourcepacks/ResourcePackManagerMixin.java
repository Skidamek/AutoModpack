package pl.skidam.automodpack.sharedresources.normal.resourcepacks;

import net.minecraft.resource.FileResourcePackProvider;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.ResourceType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.utils.ExternalFileResourcePackProvider;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import static pl.skidam.automodpack.AutoModpack.selectedModpackDir;

/**
 * Credits to enjarai for the original code (https://github.com/enjarai/shared-resources)
 */

@Mixin(ResourcePackManager.class)
public abstract class ResourcePackManagerMixin {
	@Mutable @Shadow @Final private Set<ResourcePackProvider> providers;

	@Inject(method = "<init>", at = @At(value = "RETURN"))
	private void sharedresources$initResourcePackProvider(CallbackInfo ci) {
		// Only add our own provider if this is the manager of client
		// resource packs, we wouldn't want to mess with datapacks
		if (selectedModpackDir == null) return;

		File resourcePackDir = new File(selectedModpackDir + File.separator + "resourcepacks");
		if (!resourcePackDir.exists()) return;

		if (providers.stream().anyMatch(provider -> provider instanceof FileResourcePackProvider &&
				((FileResourcePackProviderAccessor) provider).sharedresources$getResourceType() == ResourceType.CLIENT_RESOURCES)) {

			providers = new HashSet<>(providers);
			providers.add(new ExternalFileResourcePackProvider(resourcePackDir::toPath));
		}
	}
}
