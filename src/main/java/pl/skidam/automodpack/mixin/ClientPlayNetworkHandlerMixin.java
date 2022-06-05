package pl.skidam.automodpack.mixin;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.AutoModpackMain;

import java.util.concurrent.CompletableFuture;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Shadow @Final private MinecraftClient client;

    @Inject(method = "onGameJoin", at = @At("HEAD"))
    public void onGameJoin(CallbackInfo ci) {
        AutoModpackMain.LOGGER.error("I am here (CLIENT)");
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            ClientPlayNetworking.send(AutoModpackMain.PACKET_C2S, PacketByteBufs.empty());
            AutoModpackMain.LOGGER.error("Packet sent");
        });
    }
}