package pl.skidam.automodpack.mixin.dev;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;

import pl.skidam.automodpack.client.autotest.AutoTestBridge;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

	@Inject(method = "<init>", at = @At("RETURN"))
	private void onInit(GameConfig gameConfig, CallbackInfo ci) {
		AutoTestBridge.start();
	}
}
