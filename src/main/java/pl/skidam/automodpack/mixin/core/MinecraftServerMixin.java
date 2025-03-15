package pl.skidam.automodpack.mixin.core;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.init.Common;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

	/*? if >=1.19.3 {*/
	@Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;createMetadata()Lnet/minecraft/server/ServerMetadata;", ordinal = 0), method = "runServer")
/*?} else {*/
	/*@Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;setFavicon(Lnet/minecraft/server/ServerMetadata;)V", ordinal = 0), method = "runServer")
*//*?}*/
	private void afterSetupServer(CallbackInfo info) {
        Common.server = (MinecraftServer) (Object) this;
		Common.afterSetupServer();
	}

	@Inject(at = @At("HEAD"), method = "shutdown")
	private void beforeShutdownServer(CallbackInfo info) {
		Common.beforeShutdownServer();
	}
}
