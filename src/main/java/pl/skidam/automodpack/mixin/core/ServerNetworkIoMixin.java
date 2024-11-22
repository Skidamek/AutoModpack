package pl.skidam.automodpack.mixin.core;

import io.netty.channel.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack_core.netty.InterConnector;

import static pl.skidam.automodpack_core.GlobalVariables.MOD_ID;

@Mixin(targets = "net/minecraft/server/ServerNetworkIo$1", priority = 2137)
public abstract class ServerNetworkIoMixin {

    @Inject(
            method = "initChannel",
            at = @At("TAIL")
    )
    private void injectAutoModpackHost(Channel channel, CallbackInfo ci) {
        channel.pipeline().addFirst(MOD_ID, new InterConnector());
    }
}
