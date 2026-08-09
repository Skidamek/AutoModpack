package pl.skidam.automodpack.mixin.dev;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.GameRenderer;
/*? if >=1.21.1 {*/
import net.minecraft.client.DeltaTracker;
/*?}*/
import org.spongepowered.asm.mixin.Mixin;
import pl.skidam.automodpack.client.autotest.AutoTestBridge;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	/*? if >=1.21.1 {*/
	@WrapMethod(method = "render")
	private void afterRender(DeltaTracker deltaTracker, boolean renderLevel, Operation<Void> original) {
		original.call(deltaTracker, renderLevel);
		AutoTestBridge.onFrameRendered();
	}
	/*?} else {*/
	/*@WrapMethod(method = "render")
	private void afterRender(float partialTick, long nanoTime, boolean renderLevel, Operation<Void> original) {
		original.call(partialTick, nanoTime, renderLevel);
		AutoTestBridge.onFrameRendered();
	}
	*//*?}*/
}
