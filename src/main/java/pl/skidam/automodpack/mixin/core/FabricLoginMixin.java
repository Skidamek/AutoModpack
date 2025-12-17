package pl.skidam.automodpack.mixin.core;

import net.fabricmc.fabric.impl.networking.server.ServerLoginNetworkAddon;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.networking.LoginNetworkingIDs;

@Pseudo
@Mixin(value = ServerLoginNetworkAddon.class, remap = false)
public class FabricLoginMixin {

    @Inject(
            method = "registerOutgoingPacket",
            at = @At(value = "HEAD"),
            cancellable = true
    )
    private void dontRemoveAutoModpackChannels(ClientboundCustomQueryPacket packet, CallbackInfo ci) {
        /*? if <1.20.2 {*/
        /*ResourceLocation id = packet.getIdentifier();
        *//*?} else {*/
        Identifier id = packet.payload().id();
        /*?}*/
        // Cancel if it's one of our channels
        if (LoginNetworkingIDs.getByKey(id) != null) {
            ci.cancel();
        }
    }
}
