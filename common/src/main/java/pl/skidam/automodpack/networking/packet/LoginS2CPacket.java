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
import pl.skidam.automodpack.Platform;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.mixin.ServerLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.modpack.HttpServer;

import java.util.UUID;

import static pl.skidam.automodpack.StaticVariables.*;
import static pl.skidam.automodpack.networking.ModPackets.LINK;
import static pl.skidam.automodpack.networking.fabric.ModPacketsImpl.acceptLogin;

public class LoginS2CPacket {

    // Login packet (the best way)
    public static void receive(MinecraftServer server, ServerLoginNetworkHandler handler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer sync, PacketSender sender) {
        ClientConnection connection = handler.connection;

        GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();
        UUID uniqueId = profile.getId();
        String playerName = profile.getName();

        String correctResponse = VERSION + "-" + Platform.getPlatformType().toString().toLowerCase();

        if (!understood) {
            if (serverConfig.optionalModpack) {
                acceptLogin.add(uniqueId);
                LOGGER.info("{} has not installed AutoModpack.", playerName);
                return;
            }
        } else {

            String clientResponse = buf.readString();
            boolean isClientVersionHigher = isClientVersionHigher(clientResponse);

            if (!clientResponse.equals(correctResponse)) {
                if (!serverConfig.allowFabricQuiltPlayers && !clientResponse.startsWith(VERSION)) {
                    Text reason = TextHelper.literal("AutoModpack version mismatch! Install " + VERSION + " version of AutoModpack mod for " + Platform.getPlatformType().toString().toLowerCase() + " to play on this server!");
                    if (isClientVersionHigher) {
                        reason = TextHelper.literal("You are using a more recent version of AutoModpack than the server. Please contact the server administrator to update the AutoModpack mod.");
                    }
                    acceptLogin.add(uniqueId);
                    connection.send(new LoginDisconnectS2CPacket(reason));
                    connection.disconnect(reason);
                    return;
                } else if (clientResponse.startsWith(VERSION)) {
                    Text reason = TextHelper.literal("AutoModpack version mismatch! Install " + VERSION + " version of AutoModpack mod for " + Platform.getPlatformType().toString().toLowerCase() + " to play on this server!");
                    if (isClientVersionHigher) {
                        reason = TextHelper.literal("You are using a more recent version of AutoModpack than the server. Please contact the server administrator to update the AutoModpack mod.");
                    }
                    acceptLogin.add(uniqueId);
                    connection.send(new LoginDisconnectS2CPacket(reason));
                    connection.disconnect(reason);
                    return;
                }
            }
        }

        acceptLogin.add(uniqueId);

        if (!HttpServer.isRunning && serverConfig.externalModpackHostLink.equals("")) return;

        String playerIp = connection.getAddress().toString();
        String HostIPForLocal = serverConfig.hostLocalIp.replaceFirst("(https?://)", ""); // Removes HTTP:// or HTTPS://
        String HostNetwork = "";
        if (HostIPForLocal.chars().filter(ch -> ch == '.').count() > 3) {
            String[] parts = HostIPForLocal.split("\\.");
            HostNetwork = parts[0] + "." + parts[1] + "." + parts[2]; // Reduces ip from x.x.x.x to x.x.x
        }

        String linkToSend;

        if (!serverConfig.externalModpackHostLink.equals("")) {
            // If an external modpack host link has been specified, use it
            linkToSend = serverConfig.externalModpackHostLink;
            LOGGER.info("Sending external modpack host link: " + linkToSend);
        } else {
            // If the player is connecting locally or their IP matches a specified IP, use the local host IP and port
            if (playerIp.startsWith("/127.0.0.1") || playerIp.startsWith("/[0:0:0:0:") || playerIp.startsWith("/" + serverConfig.hostLocalIp)) { // local
                linkToSend = "http://" + serverConfig.hostLocalIp + ":" + serverConfig.hostPort;
            } else if (!HostNetwork.equals("") && playerIp.startsWith("/" + HostNetwork)) { // local
                linkToSend = "http://" + serverConfig.hostLocalIp + ":" + serverConfig.hostPort;
            } else { // Otherwise, use the public host IP and port
                linkToSend = "http://" + serverConfig.hostIp + ":" + serverConfig.hostPort;
            }
        }

        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeString(linkToSend);

        sender.sendPacket(LINK, outBuf);
    }

    // Join packet (velocity support)
    public static void receive(MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender sender) {
        ClientConnection connection = handler.connection;

        String correctResponse = VERSION + "-" + Platform.getPlatformType().toString().toLowerCase();
        String clientResponse = buf.readString();
        boolean isClientVersionHigher = isClientVersionHigher(clientResponse);


//        if (!understood) { TODO
//            if (AutoModpack.serverConfig.optionalModpack) {
//                acceptLogin.put(uniqueId, true);
//                AutoModpack.LOGGER.info("{} has not installed automodpack.", playerName);
//                return;
//            }
//        } else
        if (!clientResponse.equals(correctResponse)) {
            if (!serverConfig.allowFabricQuiltPlayers && !clientResponse.startsWith(VERSION)) {
                Text reason = TextHelper.literal("AutoModpack version mismatch! Install " + VERSION + " version of AutoModpack mod for " + Platform.getPlatformType().toString().toLowerCase() + " to play on this server!");
                if (isClientVersionHigher) {
                    reason = TextHelper.literal("You are using a more recent version of AutoModpack than the server. Please contact the server administrator to update the AutoModpack mod.");
                }
                connection.send(new DisconnectS2CPacket(reason));
                connection.disconnect(reason);
                return;
            } else if (clientResponse.startsWith(VERSION)) {
                Text reason = TextHelper.literal("AutoModpack version mismatch! Install " + VERSION + " version of AutoModpack mod for " + Platform.getPlatformType().toString().toLowerCase() + " to play on this server!");
                if (isClientVersionHigher) {
                    reason = TextHelper.literal("You are using a more recent version of AutoModpack than the server. Please contact the server administrator to update the AutoModpack mod.");
                }
                connection.send(new DisconnectS2CPacket(reason));
                connection.disconnect(reason);
                return;
            }
        }

        if (!HttpServer.isRunning && serverConfig.externalModpackHostLink.equals("")) return;

        String playerIp = connection.getAddress().toString();
        String HostIPForLocal = serverConfig.hostLocalIp.replaceFirst("(https?://)", ""); // Removes HTTP:// or HTTPS://
        String HostNetwork = "";
        if (HostIPForLocal.chars().filter(ch -> ch == '.').count() > 3) {
            String[] parts = HostIPForLocal.split("\\.");
            HostNetwork =  parts[0] + "." + parts[1] + "." + parts[2]; // Reduces ip from x.x.x.x to x.x.x
        }

        String linkToSend;

        if (!serverConfig.externalModpackHostLink.equals("")) {
            // If an external modpack host link has been specified, use it
            linkToSend = serverConfig.externalModpackHostLink;
            LOGGER.info("Sending external modpack host link: " + linkToSend);
        } else {
            // If the player is connecting locally or their IP matches a specified IP, use the local host IP and port
            if (playerIp.startsWith("/127.0.0.1") || playerIp.startsWith("/[0:0:0:0:") || playerIp.startsWith("/" + serverConfig.hostLocalIp)) { // local
                linkToSend = "http://" + serverConfig.hostLocalIp + ":" + serverConfig.hostPort;
            } else if (!HostNetwork.equals("") && playerIp.startsWith("/" + HostNetwork)) { // local
                linkToSend = "http://" + serverConfig.hostLocalIp + ":" + serverConfig.hostPort;
            } else { // Otherwise, use the public host IP and port
                linkToSend = "http://" + serverConfig.hostIp + ":" + serverConfig.hostPort;
            }
        }

        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeString(linkToSend);

        sender.sendPacket(LINK, outBuf);
    }


    public static boolean isClientVersionHigher(String clientResponse) {
        String clientVersion = clientResponse.substring(0, clientResponse.indexOf("-"));
        boolean isClientVersionHigher = false;

        if (!clientVersion.equals(VERSION)) {
            String[] clientVersionComponents = clientVersion.split("\\.");
            String[] serverVersionComponents = VERSION.split("\\.");

            for (int i = 0, n = clientVersionComponents.length; i < n; i++) {
                if (clientVersionComponents[i].compareTo(serverVersionComponents[i]) > 0) {
                    isClientVersionHigher = true;
                    break;
                } else if (clientVersionComponents[i].compareTo(serverVersionComponents[i]) < 0) {
                    break;
                }
            }
        }

        return isClientVersionHigher;
    }
}
