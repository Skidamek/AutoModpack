package pl.skidam.automodpack.mixin.core;

import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.networking.server.ServerLoginNetworkAddon;

@Mixin(value = ServerLoginPacketListenerImpl.class, priority = 300)
public abstract class ServerLoginNetworkHandlerMixin  {

	/*? if <= 1.20.1 {*/
    /*@org.spongepowered.asm.mixin.Shadow ServerLoginPacketListenerImpl.State state;
    *//*?}*/
    @Unique private ServerLoginNetworkAddon automodpack$addon;

    @Inject(
            method = "<init>",
            at = @At("RETURN")
    )
    private void initAddon(CallbackInfo ci) {
        this.automodpack$addon = new ServerLoginNetworkAddon((ServerLoginPacketListenerImpl) (Object) this);
    }

    @Inject(
            method = "handleCustomQueryPacket",
            at = @At("HEAD"),
            cancellable = true
    )
    private void handleCustomPayload(ServerboundCustomQueryAnswerPacket packet, CallbackInfo ci) {
        if (this.automodpack$addon == null) {
            return;
        }

        // Handle queries
        if (this.automodpack$addon.handle(packet)) {
            ci.cancel(); // We have handled it, cancel vanilla behavior
        }
    }

    /*? if > 1.20.1 {*/
    @Inject(
            method = "verifyLoginAndFinishConnectionSetup",
            at = @At("HEAD"),
            cancellable = true
    )
    private void beforeVerifyLogin(CallbackInfo ci) {
        if (this.automodpack$addon == null) {
            return;
        }

        if (!this.automodpack$addon.queryTick()) {
            ci.cancel();
            return;
        }

        this.automodpack$addon = null;
    }
    /*?} else {*/
    /*@Inject(
            method = "tick",
            at = @At("HEAD"),
            cancellable = true
    )
    private void sendOurPackets(CallbackInfo ci) {
        if (this.automodpack$addon == null) {
            return;
        }

        if (state != ServerLoginPacketListenerImpl.State.NEGOTIATING && state != ServerLoginPacketListenerImpl.State.READY_TO_ACCEPT) {
            return;
        }

        if (!this.automodpack$addon.queryTick()) {
            ci.cancel();
            return;
        }

        this.automodpack$addon = null;
    }
    *//*?}*/
}
