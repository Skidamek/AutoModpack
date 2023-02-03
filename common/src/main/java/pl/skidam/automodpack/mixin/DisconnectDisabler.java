package pl.skidam.automodpack.mixin;

import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static pl.skidam.automodpack.AutoModpack.keyWordsOfDisconnect;

@Mixin(value = ServerLoginNetworkHandler.class, priority = 2137)
public class DisconnectDisabler {
    @Inject(method = "disconnect", at = @At("HEAD"), cancellable = true)
    public void turnOffDisconnect(Text disconnectReason, CallbackInfo ci) {
        String reason = disconnectReason.toString().toLowerCase();
        if (reason.contains("automodpack")) return;

        if (keyWordsOfDisconnect.stream().anyMatch(reason::contains)) {
            ci.cancel();
        }
    }
}