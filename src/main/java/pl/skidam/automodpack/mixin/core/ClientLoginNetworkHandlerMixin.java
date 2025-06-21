package pl.skidam.automodpack.mixin.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.networking.client.ClientLoginNetworkAddon;

@Mixin(value = ClientHandshakePacketListenerImpl.class, priority = 300)
public class ClientLoginNetworkHandlerMixin {
    @Shadow @Final private Minecraft minecraft;
    @Unique
    private ClientLoginNetworkAddon autoModpack$addon;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void initAddon(CallbackInfo ci) {
        this.autoModpack$addon = new ClientLoginNetworkAddon((ClientHandshakePacketListenerImpl) (Object) this, this.minecraft);
    }

    @Inject(
            method = "handleCustomQuery",
            at = @At(value = "HEAD"),
            cancellable = true
    )
    private void handleQueryRequest(ClientboundCustomQueryPacket packet, CallbackInfo ci) {
        if (this.autoModpack$addon == null) {
            return;
        }

        if (this.autoModpack$addon.handlePacket(packet)) {
            // We have handled it, cancel vanilla behavior
            ci.cancel();
        }
    }
}
