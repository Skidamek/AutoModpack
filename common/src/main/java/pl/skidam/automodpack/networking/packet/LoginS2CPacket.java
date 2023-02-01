package pl.skidam.automodpack.networking.packet;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.Platform;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.mixin.ServerLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.modpack.HttpServer;

import java.util.UUID;

import static pl.skidam.automodpack.networking.ModPackets.LINK;
import static pl.skidam.automodpack.networking.fabric.ModPacketsImpl.acceptLogin;

public class LoginS2CPacket {


    public static void receive(MinecraftServer server, ServerLoginNetworkHandler handler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer sync, PacketSender sender) {
        packetLogic(server, handler.connection, understood, buf, sync, sender, handler);
    }

    public static void receive(MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender sender) {
        packetLogic(server, handler.connection, true, buf, null, sender);
    }


    private static void packetLogic(MinecraftServer server, ClientConnection connection, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer sync, PacketSender sender, ServerLoginNetworkHandler... loginHandler) {

        UUID uniqueId = null;

        if (loginHandler.length == 1) {
            for (ServerLoginNetworkHandler handler : loginHandler) {
                GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();
                uniqueId = profile.getId();
            }
        }

        String correctResponse = AutoModpack.VERSION + "-" + Platform.getPlatformType().toString().toLowerCase();

        if (!understood || !buf.readString().equals(correctResponse)) {
            if (uniqueId != null) acceptLogin.put(uniqueId, false);
            if (AutoModpack.serverConfig.optionalModpack) return;
            Text reason = TextHelper.literal("AutoModpack version mismatch! Install " + AutoModpack.VERSION + " version of AutoModpack mod for " + Platform.getPlatformType().toString().toLowerCase() + " to play on this server!");
            connection.send(new LoginDisconnectS2CPacket(reason));
            connection.disconnect(reason);
        } else {
            if (uniqueId != null) acceptLogin.put(uniqueId, true);
            AutoModpack.LOGGER.info("AutoModpack version match!");

            if (!HttpServer.isRunning && AutoModpack.serverConfig.externalModpackHostLink.equals("")) return;

            String playerIp = connection.getAddress().toString();
            String HostIPForLocal = AutoModpack.serverConfig.hostLocalIp.replaceFirst("(https?://)", ""); // Removes HTTP:// or HTTPS://
            String[] parts = HostIPForLocal.split("\\.");
            String HostNetwork =  parts[0] + "." + parts[1] + "." + parts[2]; // Reduces ip from x.x.x.x to x.x.x

            String linkToSend;

            if (!AutoModpack.serverConfig.externalModpackHostLink.equals("")) {
                // If an external modpack host link has been specified, use it
                linkToSend = AutoModpack.serverConfig.externalModpackHostLink;
                AutoModpack.LOGGER.info("Sending external modpack host link: " + linkToSend);
            } else {
                // If the player is connecting locally or their IP matches a specified IP, use the local host IP and port
                if (playerIp.startsWith("/127.0.0.1") || playerIp.startsWith("/" + HostNetwork)) {
                    linkToSend = "http://" + AutoModpack.serverConfig.hostLocalIp + ":" + AutoModpack.serverConfig.hostPort;
                } else { // Otherwise, use the public host IP and port
                    linkToSend = "http://" + AutoModpack.serverConfig.hostIp + ":" + AutoModpack.serverConfig.hostPort;
                }
            }



            PacketByteBuf outBuf = PacketByteBufs.create();
            outBuf.writeString(linkToSend);

            sender.sendPacket(LINK, outBuf);
        }
    }
}
