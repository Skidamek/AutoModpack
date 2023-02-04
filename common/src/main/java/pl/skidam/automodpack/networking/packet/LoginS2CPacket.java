package pl.skidam.automodpack.networking.packet;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.DisconnectS2CPacket;
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


    // Login packet (the best way)
    public static void receive(MinecraftServer server, ServerLoginNetworkHandler handler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer sync, PacketSender sender) {
        ClientConnection connection = handler.connection;

        GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();
        UUID uniqueId = profile.getId();
        String playerName = profile.getName();

        String correctResponse = AutoModpack.VERSION + "-" + Platform.getPlatformType().toString().toLowerCase();

        if (!understood || !buf.readString().equals(correctResponse)) {
            if (AutoModpack.serverConfig.optionalModpack) {
                acceptLogin.put(uniqueId, true);
                AutoModpack.LOGGER.info("{} has not installed automodpack.", playerName);
                return;
            }
            Text reason = TextHelper.literal("AutoModpack version mismatch! Install " + AutoModpack.VERSION + " version of AutoModpack mod for " + Platform.getPlatformType().toString().toLowerCase() + " to play on this server!");
            connection.send(new LoginDisconnectS2CPacket(reason));
            connection.disconnect(reason);
            acceptLogin.put(uniqueId, false);
            return;
        }

        acceptLogin.put(uniqueId, true);

        if (!HttpServer.isRunning && AutoModpack.serverConfig.externalModpackHostLink.equals("")) return;

        String playerIp = connection.getAddress().toString();
        String HostIPForLocal = AutoModpack.serverConfig.hostLocalIp.replaceFirst("(https?://)", ""); // Removes HTTP:// or HTTPS://
        String HostNetwork = "";
        if (HostIPForLocal.chars().filter(ch -> ch == '.').count() > 3) {
            String[] parts = HostIPForLocal.split("\\.");
            HostNetwork = parts[0] + "." + parts[1] + "." + parts[2]; // Reduces ip from x.x.x.x to x.x.x
        }

        String linkToSend;

        if (!AutoModpack.serverConfig.externalModpackHostLink.equals("")) {
            // If an external modpack host link has been specified, use it
            linkToSend = AutoModpack.serverConfig.externalModpackHostLink;
            AutoModpack.LOGGER.info("Sending external modpack host link: " + linkToSend);
        } else {
            // If the player is connecting locally or their IP matches a specified IP, use the local host IP and port
            if (playerIp.startsWith("/127.0.0.1")) { // local
                linkToSend = "http://" + AutoModpack.serverConfig.hostLocalIp + ":" + AutoModpack.serverConfig.hostPort;
            } else if (!HostNetwork.equals("") && playerIp.startsWith("/" + HostNetwork)) { // local
                linkToSend = "http://" + AutoModpack.serverConfig.hostLocalIp + ":" + AutoModpack.serverConfig.hostPort;
            } else { // Otherwise, use the public host IP and port
                linkToSend = "http://" + AutoModpack.serverConfig.hostIp + ":" + AutoModpack.serverConfig.hostPort;
            }
        }

        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeString(linkToSend);

        sender.sendPacket(LINK, outBuf);
    }

    // Join packet (velocity support)
    public static void receive(MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender sender) {
        ClientConnection connection = handler.connection;
        String correctResponse = AutoModpack.VERSION + "-" + Platform.getPlatformType().toString().toLowerCase();

        if (!buf.readString().equals(correctResponse)) {
            if (AutoModpack.serverConfig.optionalModpack) {
                AutoModpack.LOGGER.info("{} has not installed automodpack.", player.getName().getString());
                return;
            }
            Text reason = TextHelper.literal("AutoModpack version mismatch! Install " + AutoModpack.VERSION + " version of AutoModpack mod for " + Platform.getPlatformType().toString().toLowerCase() + " to play on this server!");
            connection.send(new DisconnectS2CPacket(reason));
            connection.disconnect(reason);
            return;
        }

        if (!HttpServer.isRunning && AutoModpack.serverConfig.externalModpackHostLink.equals("")) return;

        String playerIp = connection.getAddress().toString();
        String HostIPForLocal = AutoModpack.serverConfig.hostLocalIp.replaceFirst("(https?://)", ""); // Removes HTTP:// or HTTPS://
        String HostNetwork = "";
        if (HostIPForLocal.chars().filter(ch -> ch == '.').count() > 3) {
            String[] parts = HostIPForLocal.split("\\.");
            HostNetwork =  parts[0] + "." + parts[1] + "." + parts[2]; // Reduces ip from x.x.x.x to x.x.x
        }

        String linkToSend;

        if (!AutoModpack.serverConfig.externalModpackHostLink.equals("")) {
            // If an external modpack host link has been specified, use it
            linkToSend = AutoModpack.serverConfig.externalModpackHostLink;
            AutoModpack.LOGGER.info("Sending external modpack host link: " + linkToSend);
        } else {
            // If the player is connecting locally or their IP matches a specified IP, use the local host IP and port
            if (playerIp.startsWith("/127.0.0.1")) { // local
                linkToSend = "http://" + AutoModpack.serverConfig.hostLocalIp + ":" + AutoModpack.serverConfig.hostPort;
            } else if (!HostNetwork.equals("") && playerIp.startsWith("/" + HostNetwork)) { // local
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
