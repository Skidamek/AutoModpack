package pl.skidam.automodpack.mixin.core;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.init.Common;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

	@Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;initServer()Z", shift = At.Shift.AFTER), method = "runServer")
	private void afterSetupServer(CallbackInfo info) {
        Common.server = (MinecraftServer) (Object) this;
		Common.afterSetupServer();
	}

	@Inject(at = @At("HEAD"), method = "stopServer")
	private void beforeShutdownServer(CallbackInfo info) {
		Common.beforeShutdownServer();
	}
}
