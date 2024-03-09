package pl.skidam.automodpack.mixin.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.packet.s2c.login.LoginQueryRequestS2CPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.networking.client.ClientLoginNetworkAddon;

@Mixin(value = ClientLoginNetworkHandler.class, priority = 300)
public class ClientLoginNetworkHandlerMixin {
    @Shadow @Final private MinecraftClient client;
    @Unique
    private ClientLoginNetworkAddon autoModpack$addon;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void initAddon(CallbackInfo ci) {
        this.autoModpack$addon = new ClientLoginNetworkAddon((ClientLoginNetworkHandler) (Object) this, this.client);
    }

    @Inject(
            method = "onQueryRequest",
            at = @At(value = "HEAD"),
            cancellable = true
    )
    private void handleQueryRequest(LoginQueryRequestS2CPacket packet, CallbackInfo ci) {
        if (this.autoModpack$addon.handlePacket(packet)) {
            // We have handled it, cancel vanilla behavior
            ci.cancel();
        }
    }
}
