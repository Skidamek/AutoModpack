package pl.skidam.automodpack.mixin.core;

import static pl.skidam.automodpack_core.Constants.*;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import io.netty.channel.Channel;

import pl.skidam.automodpack.init.Common;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.protocol.ServerHolepunchBridge;
import pl.skidam.automodpack_core.protocol.netty.handler.ProtocolServerHandler;
import pl.skidam.mcholepunch.server.netty.NettyLoginClaimHandler;
import pl.skidam.mcholepunch.server.netty.NettyTakeoverSpec;

@Mixin(targets = "net/minecraft/server/network/ServerConnectionListener$1", priority = 2137)
public abstract class ServerNetworkIoMixin {

	@WrapMethod(method = "initChannel")
	private void injectAutoModpackHost(Channel channel, Operation<Void> original) {
		original.call(channel);
		if (hostServer != null && hostServer.isSharedMagicEnabled()) {
			channel.pipeline().addFirst(MOD_ID, new ProtocolServerHandler(hostServer, ModpackConnectionMode.MAGIC_PACKET, true));
			return;
		}
		if (Common.server != null && ServerHolepunchBridge.isRegistered()) {
			channel.pipeline().addAfter(NettyTakeoverSpec.minecraftLogin().inboundEncryptionAnchor(), MOD_ID, new NettyLoginClaimHandler(Common.server.getKeyPair()));
		}
	}
}
