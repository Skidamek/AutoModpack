package pl.skidam.automodpack.mixin.core;

import net.minecraft.network.packet.c2s.login.LoginQueryResponseC2SPacket;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.networking.server.ServerLoginNetworkAddon;

////#if MC > 1165
//import org.slf4j.Logger;
////#else
////$$ import org.apache.logging.log4j.Logger;
////#endif

@Mixin(value = ServerLoginNetworkHandler.class, priority = 300)
public abstract class ServerLoginNetworkHandlerMixin  {

    @Shadow private int loginTicks;
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
            // We have handled it, cancel vanilla behavior
            ci.cancel();
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

//#if MC < 1202
//$$        if (state != ServerLoginNetworkHandler.State.NEGOTIATING && state != ServerLoginNetworkHandler.State.READY_TO_ACCEPT) {
//$$            return;
//$$        }
//#else
        if (state != ServerLoginNetworkHandler.State.NEGOTIATING && state != ServerLoginNetworkHandler.State.VERIFYING) {
            return;
        }
//#endif

        // Send first automodpack packet
        if (!this.automodpack$addon.queryTick()) {
            // We need more time to process packets
            ci.cancel();
            this.loginTicks++;
            return;
        }

        this.automodpack$addon = null;
    }

//    @WrapWithCondition(
//            method = "onDisconnected",
//            //#if MC > 1165
//            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V")
//            //#else
//            //$$ at = @At(value = "INVOKE", target = "Lorg/apache/logging/log4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V")
//            //#endif
//    )
//    private boolean changeText(Logger instance, String original, Object connectionInfo, Object reason) {
//        if (this.automodpack$addon == null || this.profile == null) {
//            return true;
//        }
//
//        this.automodpack$addon = null;
//        instance.info(original, this.profile.getName(), "Need to install Modpack via AutoModpack");
//        return false;
//    }
}
