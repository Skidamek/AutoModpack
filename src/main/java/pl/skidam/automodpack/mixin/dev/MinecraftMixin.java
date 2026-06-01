package pl.skidam.automodpack.mixin.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.main.GameConfig;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.client.autotest.AutoTestBridge;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

	@Inject(method = "<init>", at = @At("RETURN"))
	private void onInit(GameConfig gameConfig, CallbackInfo ci) {
		AutoTestBridge.startIfEnabled();

		Thread t = new Thread(() -> {
			while (true) {
				try {
					Thread.sleep(100);
					Minecraft mc = Minecraft.getInstance();
					if (mc.screen instanceof TitleScreen) {
						System.out.println("AutoModpack: Client is ready, TitleScreen detected");
						AutoTestBridge.onClientReady();
						return;
					} else {
						System.out.println("AutoModpack: Waiting for TitleScreen, current screen: " + (mc.screen == null ? "null" : mc.screen.getClass().getName()));
					}
				} catch (Exception ignored) {
				}
			}
		}, "AutoModpackReadyWaiter");
		t.setDaemon(true);
		t.start();
	}
}
