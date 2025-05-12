package pl.skidam.automodpack.mixin.core;

import net.minecraft.network.packet.c2s.login.LoginQueryResponseC2SPacket;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.networking.client.LoginResponsePayload;
import pl.skidam.automodpack.networking.server.ServerLoginNetworkAddon;

@Mixin(value = ServerLoginNetworkHandler.class, priority = 300)
public abstract class ServerLoginNetworkHandlerMixin  {

    @Shadow private ServerLoginNetworkHandler.State state;
    @Unique private ServerLoginNetworkAddon automodpack$addon;

    @Inject(
            method = "<init>",
            at = @At("RETURN")
    )
    private void initAddon(CallbackInfo ci) {
        this.automodpack$addon = new ServerLoginNetworkAddon((ServerLoginNetworkHandler) (Object) this);
    }

    @Inject(
            method = "onQueryResponse",
            at = @At("HEAD"),
            cancellable = true
    )
    private void handleCustomPayload(LoginQueryResponseC2SPacket packet, CallbackInfo ci) {
        if (this.automodpack$addon == null) {
            return;
        }

        // Handle queries
        if (this.automodpack$addon.handle(packet)) {
            ci.cancel(); // We have handled it, cancel vanilla behavior
        } else {
            /*? if >=1.20.2 {*/
            if (packet.response() instanceof LoginResponsePayload response) {
                response.data().skipBytes(response.data().readableBytes());
            }
            /*?}*/
        }
    }

    @Inject(
            method = "tick",
            at = @At(value = "HEAD"),
            cancellable = true
    )
    private void sendOurPackets(CallbackInfo ci) {
        if (this.automodpack$addon == null) {
            return;
        }

        if (state != ServerLoginNetworkHandler.State.NEGOTIATING && state != ServerLoginNetworkHandler.State./*? if <1.20.2 {*/ /*READY_TO_ACCEPT *//*?} else {*/VERIFYING/*?}*/) {
            return;
        }

        // Send first automodpack packet
        if (!this.automodpack$addon.queryTick()) {
            // We need more time to process packets
            ci.cancel();
            return;
        }

        this.automodpack$addon = null;
    }

}
