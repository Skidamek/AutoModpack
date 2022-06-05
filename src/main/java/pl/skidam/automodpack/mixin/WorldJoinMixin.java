package pl.skidam.automodpack.mixin;

import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.AutoModpackMain;

import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackServer.PlayersHavingAM;

@Mixin(PlayerManager.class)
public class WorldJoinMixin
{
    @Inject(at = @At("TAIL"), method = "onPlayerConnect(Lnet/minecraft/network/ClientConnection;Lnet/minecraft/server/network/ServerPlayerEntity;)V")
    public void onLoginStart(ClientConnection connection, ServerPlayerEntity playerEntity, CallbackInfo info)
    {
        AutoModpackMain.LOGGER.error(playerEntity.getName().asString() + " joined, LETS GO!");
        ServerPlayNetworking.send(playerEntity, AutoModpackMain.PACKET_S2C, PacketByteBufs.empty());

        // kick player if he doesn't have AutoModpack
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1000);

                if (PlayersHavingAM.contains(playerEntity.getName().asString())) {
                    AutoModpackMain.LOGGER.error(playerEntity.getName().asString() + " is already have AutoModpack!");
                    PlayersHavingAM.remove(playerEntity.getName().asString());
                } else {
                    Text DisconnectText = Text.of("You have to install \"AutoModpack\" mod to play on this server! https://github.com/Skidamek/AutoModpack/releases");
                    AutoModpackMain.LOGGER.error(playerEntity.getName().asString() + " not have AutoModpack!");
                    playerEntity.networkHandler.disconnect(DisconnectText);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
    }
}
