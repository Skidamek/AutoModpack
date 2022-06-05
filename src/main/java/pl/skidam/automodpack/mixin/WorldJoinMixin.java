package pl.skidam.automodpack.mixin;

import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.AutoModpackMain;

@Mixin(PlayerManager.class)
public class WorldJoinMixin
{
    @Inject(at = @At("TAIL"), method = "onPlayerConnect(Lnet/minecraft/network/ClientConnection;Lnet/minecraft/server/network/ServerPlayerEntity;)V")
    private void onLoginStart(ClientConnection connection, ServerPlayerEntity playerEntity, CallbackInfo info)
    {
        AutoModpackMain.LOGGER.error(playerEntity.getName().asString() + " joined, LETS GO!");

        ServerPlayNetworking.send(playerEntity, AutoModpackMain.PACKET_S2C, PacketByteBufs.empty());

    }
}
