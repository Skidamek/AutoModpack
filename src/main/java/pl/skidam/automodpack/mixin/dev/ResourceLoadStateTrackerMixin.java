package pl.skidam.automodpack.mixin.dev;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.ResourceLoadStateTracker;
import net.minecraft.client.gui.screens.TitleScreen;

import pl.skidam.automodpack.client.autotest.AutoTestBridge;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

@Mixin(ResourceLoadStateTracker.class)
public class ResourceLoadStateTrackerMixin {

	@WrapMethod(method = "finishReload")
	private void onFinishReload(Operation<Void> original) {
		original.call();
		AutoTestBridge.markReloadFinished();
		if (ScreenManager.getScreen().orElse(null) instanceof TitleScreen) AutoTestBridge.onClientReady();
	}
}
