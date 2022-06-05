package pl.skidam.automodpack.mixin;

import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.AutoModpackMain;

import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackMain.time_out;
import static pl.skidam.automodpack.AutoModpackServer.PlayersHavingAM;

@Mixin(PlayerManager.class)
public class WorldJoinMixin
{
    @Inject(at = @At("TAIL"), method = "onPlayerConnect(Lnet/minecraft/network/ClientConnection;Lnet/minecraft/server/network/ServerPlayerEntity;)V")
    public void onLoginStart(ClientConnection connection, ServerPlayerEntity playerEntity, CallbackInfo info)
    {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(AutoModpackMain.link);

        ServerPlayNetworking.send(playerEntity, AutoModpackMain.PACKET_S2C, buf);

        // kick player if he doesn't have AutoModpack
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(time_out);

                if (PlayersHavingAM.contains(playerEntity.getName().asString())) {
                    AutoModpackMain.LOGGER.info(playerEntity.getName().asString() + " has AutoModpack!");
                    PlayersHavingAM.remove(playerEntity.getName().asString());
                } else {
                    Text DisconnectText = Text.of("You have to install \"AutoModpack\" mod to play on this server! https://github.com/Skidamek/AutoModpack/releases");
                    AutoModpackMain.LOGGER.info(playerEntity.getName().asString() + " has not AutoModpack! kicked!");
                    playerEntity.networkHandler.disconnect(DisconnectText);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
    }
}
