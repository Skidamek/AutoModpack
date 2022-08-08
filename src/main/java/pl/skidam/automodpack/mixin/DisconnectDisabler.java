package pl.skidam.automodpack.mixin;

import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.AutoModpackMain;

@Mixin(ServerLoginNetworkHandler.class)
public class DisconnectDisabler {

    @Inject(method = "disconnect", at = @At("HEAD"), cancellable = true)
    public void turnOffDisconnect(Text reason, CallbackInfo ci) {
        if (AutoModpackMain.isVelocity) {
            if (reason.toString().toLowerCase().contains("install") || reason.toString().toLowerCase().contains("update") || reason.toString().toLowerCase().contains("download")) {
                if (!reason.toString().toLowerCase().contains("automodpack")) {
                    ci.cancel();
                }
            }
        }
    }
}