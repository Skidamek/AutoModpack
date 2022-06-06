package pl.skidam.automodpack.mixin;

import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.server.HostModpack;

import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackMain.time_out;
import static pl.skidam.automodpack.AutoModpackServer.PlayersHavingAM;

@Mixin(PlayerManager.class)
public class WorldJoinMixin
{
    @Shadow @Final private static Logger LOGGER;

    @Inject(at = @At("TAIL"), method = "onPlayerConnect(Lnet/minecraft/network/ClientConnection;Lnet/minecraft/server/network/ServerPlayerEntity;)V")
    public void onLoginStart(ClientConnection connection, ServerPlayerEntity playerEntity, CallbackInfo info)
    {
        // get minecraft player ip if player is in local network give him local address to modpack
        String playerIp = connection.getAddress().toString();

        PacketByteBuf buf = PacketByteBufs.create();

        if (playerIp.contains("127.0.0.1")) {
            buf.writeString(HostModpack.modpackHostIpForLocalPlayers);
        } else {
            buf.writeString(AutoModpackMain.link);
        }

        ServerPlayNetworking.send(playerEntity, AutoModpackMain.PACKET_S2C, buf);

        LOGGER.info("Sent modpack link to {}.", playerEntity.getName().asString());

        // kick player if he doesn't have AutoModpack
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(time_out);

                if (PlayersHavingAM.contains(playerEntity.getName().asString())) {
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
