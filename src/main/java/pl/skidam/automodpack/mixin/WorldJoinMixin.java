package pl.skidam.automodpack.mixin;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.listener.ServerLoginPacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.TypedActionResult;
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

        // send packet to client
//        connection.sendPacket(Sit.VERSION_CHECK, PacketByteBufs.empty());

//        ServerPlayNetworking.send((ServerPlayerEntity), AutoModpackMain.PACKET_S2C, PacketByteBufs.empty());

        // send packet to client
        ServerPlayNetworking.send(playerEntity, AutoModpackMain.PACKET_S2C, PacketByteBufs.empty());


    }


}
