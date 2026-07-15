package pl.skidam.automodpack.mixin.core;

import static pl.skidam.automodpack_core.Constants.*;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.channel.Channel;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.protocol.netty.handler.ProtocolServerHandler;

@Mixin(targets = "net/minecraft/server/network/ServerConnectionListener$1", priority = 2137)
public abstract class ServerNetworkIoMixin {

	@Inject(method = "initChannel", at = @At("TAIL"))
	private void injectAutoModpackHost(Channel channel, CallbackInfo ci) {
		if (serverConfig.bindPort != -1) { return; }

		if (!serverConfig.modpackHost) { return; }

		if (!hostServer.shouldHost()) { return; }

		channel.pipeline().addFirst(MOD_ID, new ProtocolServerHandler(Constants.hostServer.getSslCtx()));
	}
}
