package pl.skidam.automodpack.networking.packet;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import net.minecraft.server.*;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.init.Common;
import pl.skidam.automodpack.mixin.core.ServerLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.content.DataPacket;
import pl.skidam.automodpack.networking.content.HandshakePacket;
import pl.skidam.automodpack.networking.PacketSender;
import pl.skidam.automodpack.networking.server.ServerLoginNetworking;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_core.utils.Ip;

import static pl.skidam.automodpack.networking.ModPackets.DATA;
import static pl.skidam.automodpack_core.GlobalVariables.*;

public class HandshakeS2CPacket {

    public static void receive(MinecraftServer server, ServerLoginNetworkHandler handler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {
        ClientConnection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();

        GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();
        String playerName = profile.getName();

// TODO: send this packet only if player can join (isnt banned, is whitelisted, etc.)
//  at the moment it's not possible because of
//  'Cannot invoke "java.util.UUID.toString()" because the return value of "com.mojang.authlib.GameProfile.getId()" is null'
//
//        SocketAddress playerIp = connection.getAddress();
//
//        if (server.getPlayerManager().checkCanJoin(playerIp, profile) != null) {
//            LOGGER.info("Not providing modpack for {}", playerName);
//            return;
//        }

        if (!understood) {
            Common.players.put(playerName, false);
            LOGGER.warn("{} has not installed AutoModpack.", playerName);
            if (serverConfig.requireAutoModpackOnClient) {
                Text reason = VersionedText.literal("AutoModpack mod for " + LOADER_MANAGER.getPlatformType().toString().toLowerCase() + " modloader is required to play on this server!");
                connection.send(new LoginDisconnectS2CPacket(reason));
                connection.disconnect(reason);
            }
        } else {
            Common.players.put(playerName, true);
            loginSynchronizer.waitFor(server.submit(() -> handleHandshake(connection, playerName, server.getServerPort(), buf, sender)));
        }
    }

    public static void handleHandshake(ClientConnection connection, String playerName, int minecraftServerPort, PacketByteBuf buf, PacketSender packetSender) {
        LOGGER.info("{} has installed AutoModpack.", playerName);

        String clientResponse = buf.readString(Short.MAX_VALUE);
        HandshakePacket clientHandshakePacket = HandshakePacket.fromJson(clientResponse);

        boolean isAcceptedLoader = false;
        for (String loader : serverConfig.acceptedLoaders) {
            if (clientHandshakePacket.loaders.contains(loader)) {
                isAcceptedLoader = true;
                break;
            }
        }

        if (!isAcceptedLoader || !clientHandshakePacket.amVersion.equals(AM_VERSION)) {
            Text reason = VersionedText.literal("AutoModpack version mismatch! Install " + AM_VERSION + " version of AutoModpack mod for " + LOADER_MANAGER.getPlatformType().toString().toLowerCase() + " to play on this server!");
            if (isClientVersionHigher(clientHandshakePacket.amVersion)) {
                reason = VersionedText.literal("You are using a more recent version of AutoModpack than the server. Please contact the server administrator to update the AutoModpack mod.");
            }
            connection.send(new LoginDisconnectS2CPacket(reason));
            connection.disconnect(reason);
            return;
        }

        if (!httpServer.shouldRunInternally()) {
            return;
        }

        if (modpack.isGenerating()) {
            Text reason = VersionedText.literal("AutoModapck is generating modpack. Please wait a moment and try again.");
            connection.send(new LoginDisconnectS2CPacket(reason));
            connection.disconnect(reason);
            return;
        }

        String playerIp = connection.getAddress().toString();

        String linkToSend;

        // If the player is connecting locally or their IP matches a specified IP, use the local host IP and port
        String formattedPlayerIp = Ip.normalizeIp(playerIp);

        if (Ip.isLocal(formattedPlayerIp)) { // local
            linkToSend = serverConfig.hostLocalIp;
        } else { // Otherwise, use the public host IP and port
            linkToSend = serverConfig.hostIp;
        }

        // We send empty string if hostIp/hostLocalIp is not specified in server config. Client will use ip by which it connected to the server in first place.
        DataPacket dataPacket = new DataPacket("", serverConfig.modpackName, serverConfig.requireAutoModpackOnClient);

        if (linkToSend != null && !linkToSend.isBlank()) {
            if (!linkToSend.startsWith("http://") && !linkToSend.startsWith("https://")) {
                linkToSend = "http://" + linkToSend;
            }

            if (serverConfig.reverseProxy) {
                // With reverse proxy we dont append port to the link, it should be already included in the link
                // But we need to check if the port is set in the config, since that's where modpack is actually hosted
                if (serverConfig.hostPort == -1) {
                    LOGGER.error("Reverse proxy is enabled but host port is not set in config! Please set it manually.");
                }
            } else { // Append server port
                if (serverConfig.hostModpackOnMinecraftPort) {
                    linkToSend += ":" + minecraftServerPort;
                } else  {
                    linkToSend += ":" + serverConfig.hostPort;

                    if (serverConfig.hostPort == -1) {
                        LOGGER.error("Host port is not set in config! Please set it manually.");
                    }
                }
            }

            LOGGER.info("Sending {} modpack link: {}", playerName, linkToSend);
            dataPacket = new DataPacket(linkToSend, serverConfig.modpackName, serverConfig.requireAutoModpackOnClient);
        }

        String packetContentJson = dataPacket.toJson();

        PacketByteBuf outBuf = new PacketByteBuf(Unpooled.buffer());
        outBuf.writeString(packetContentJson, Short.MAX_VALUE);
        packetSender.sendPacket(DATA, outBuf);
    }


    public static boolean isClientVersionHigher(String clientVersion) {

        String versionPattern = "\\d+\\.\\d+\\.\\d+";
        if (!clientVersion.matches(versionPattern)) {
            return false;
        }

        if (!clientVersion.equals(AM_VERSION)) {
            String[] clientVersionComponents = clientVersion.split("\\.");
            String[] serverVersionComponents = AM_VERSION.split("\\.");

            for (int i = 0, n = clientVersionComponents.length; i < n; i++) {
                if (clientVersionComponents[i].compareTo(serverVersionComponents[i]) > 0) {
                    return true;
                }
            }
        }

        return false;
    }
}
