package pl.skidam.automodpack.mixin.dev;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.ResourceLoadStateTracker;
import net.minecraft.client.gui.screens.TitleScreen;

import pl.skidam.automodpack.client.autotest.AutoTestBridge;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

@Mixin(ResourceLoadStateTracker.class)
public class ResourceLoadStateTrackerMixin {

	@Inject(method = "finishReload", at = @At("RETURN"))
	private void onFinishReload(CallbackInfo ci) {
		AutoTestBridge.markReloadFinished();
		if (new ScreenManager().getScreen().orElse(null) instanceof TitleScreen) { AutoTestBridge.onClientReady(); }
	}
}
