package pl.skidam.automodpack.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.MinecraftServer;

import pl.skidam.automodpack.init.Common;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

	@WrapOperation(method = "runServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;initServer()Z"))
	private boolean afterSetupServer(MinecraftServer server, Operation<Boolean> original) {
		boolean initialized = original.call(server);
		Common.server = server;
		Common.afterSetupServer();
		return initialized;
	}

	@WrapMethod(method = "stopServer")
	private void beforeShutdownServer(Operation<Void> original) {
		Common.beforeShutdownServer();
		original.call();
	}
}
