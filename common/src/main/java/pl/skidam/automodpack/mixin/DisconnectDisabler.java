package pl.skidam.automodpack.mixin;

import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
@Mixin(value = ServerLoginNetworkHandler.class, priority = 2137)
public class DisconnectDisabler {
    private final List<String> keyWords = List.of("install", "update", "download", "handshake", "incompatible", "outdated", "client", "version");
    @Inject(method = "disconnect", at = @At("HEAD"), cancellable = true)
    public void turnOffDisconnect(Text disconnectReason, CallbackInfo ci) {
        String reason = disconnectReason.toString().toLowerCase();
        if (!reason.contains("automodpack")) {
            for (String keyword : keyWords) {
                if (reason.contains(keyword)) {
                    ci.cancel();
                    break;
                }
            }
        }
    }
}