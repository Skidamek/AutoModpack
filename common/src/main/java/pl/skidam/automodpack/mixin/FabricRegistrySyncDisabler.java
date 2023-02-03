package pl.skidam.automodpack.mixin;

import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;
import net.fabricmc.fabric.impl.registry.sync.packet.RegistryPacketHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.AutoModpack;

@Mixin(value = RegistrySyncManager.class, priority = 2137)
public class FabricRegistrySyncDisabler {
    @Inject(method = "sendPacket(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/fabricmc/fabric/impl/registry/sync/packet/RegistryPacketHandler;)V", at = @At("HEAD"), cancellable = true)
    private static void sendPacketInject(ServerPlayerEntity player, RegistryPacketHandler handler, CallbackInfo ci) {

        if (AutoModpack.serverConfig.velocityMode) {
            ci.cancel();
        }
    }
}