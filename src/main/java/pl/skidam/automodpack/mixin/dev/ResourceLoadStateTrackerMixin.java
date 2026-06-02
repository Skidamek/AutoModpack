package pl.skidam.automodpack.mixin.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.ResourceLoadStateTracker;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.client.autotest.AutoTestBridge;

@Mixin(ResourceLoadStateTracker.class)
public class ResourceLoadStateTrackerMixin {

	@Inject(method = "finishReload", at = @At("RETURN"))
	private void onFinishReload(CallbackInfo ci) {
		AutoTestBridge.markReloadFinished();
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof TitleScreen) {
			System.out.println("AutoModpack: Client is ready, reload finished, TitleScreen detected");
			AutoTestBridge.onClientReady();
		}
	}
}
