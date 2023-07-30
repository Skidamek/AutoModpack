/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

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
import net.minecraft.text.Text;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.loaders.Loader;
import pl.skidam.automodpack.mixin.ServerLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.modpack.HttpServer;
import pl.skidam.automodpack.modpack.Modpack;
import pl.skidam.automodpack.utils.Ip;

import static pl.skidam.automodpack.GlobalVariables.*;
import static pl.skidam.automodpack.networking.ModPackets.LINK;

public class LoginS2CPacket {

    public static void receive(MinecraftServer minecraftServer, ServerLoginNetworkHandler serverLoginNetworkHandler, boolean b, PacketByteBuf packetByteBuf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender packetSender) {
        PacketByteBuf packet = packet(serverLoginNetworkHandler, b, packetByteBuf);
        if (packet != null) {
            packetSender.sendPacket(LINK, packet);
        }
    }

    private static PacketByteBuf packet(ServerLoginNetworkHandler handler, boolean understood, PacketByteBuf buf) {
        ClientConnection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();

        GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();
        String playerName = profile.getName();

        String correctResponse = AM_VERSION + "-" + Loader.getPlatformType().toString().toLowerCase();

        if (!understood) {
            LOGGER.warn("{} has not installed AutoModpack.", playerName);
            if (!serverConfig.optionalModpack) {
                Text reason = VersionedText.common.literal("AutoModpack mod for " + Loader.getPlatformType().toString().toLowerCase() + " modloader is required to play on this server!");
                connection.send(new LoginDisconnectS2CPacket(reason));
                connection.disconnect(reason);
            }
            return null;
        } else {

            LOGGER.info("{} has installed AutoModpack.", playerName);

            String clientResponse = buf.readString(32767);
            boolean isClientVersionHigher = isClientVersionHigher(clientResponse);

            if (!clientResponse.equals(correctResponse)) {

                boolean isAcceptedLoader = false;

                for (String loader : serverConfig.acceptedLoaders) {
                    if (clientResponse.contains(loader)) {
                        isAcceptedLoader = true;
                        break;
                    }
                }

                if (!clientResponse.startsWith(AM_VERSION) || !isAcceptedLoader) {
                    Text reason = VersionedText.common.literal("AutoModpack version mismatch! Install " + AM_VERSION + " version of AutoModpack mod for " + Loader.getPlatformType().toString().toLowerCase() + " to play on this server!");
                    if (isClientVersionHigher) {
                        reason = VersionedText.common.literal("You are using a more recent version of AutoModpack than the server. Please contact the server administrator to update the AutoModpack mod.");
                    }
                    connection.send(new LoginDisconnectS2CPacket(reason));
                    connection.disconnect(reason);
                    return null;
                }
            }
        }

        if (!HttpServer.isRunning() && serverConfig.externalModpackHostLink.equals("")) {
            return null;
        }

        if (Modpack.isGenerating()) {
            Text reason = VersionedText.common.literal("AutoModapck is generating modpack. Please wait a moment and try again.");
            connection.send(new LoginDisconnectS2CPacket(reason));
            connection.disconnect(reason);
            return null;
        }

        String playerIp = connection.getAddress().toString();

        String linkToSend;

        if (!serverConfig.externalModpackHostLink.isEmpty()) {
            // If an external modpack host link has been specified, use it
            linkToSend = serverConfig.externalModpackHostLink;
            if (!linkToSend.startsWith("http://") && !linkToSend.startsWith("https://")) {
                linkToSend = "http://" + linkToSend;
            }
        } else {
            // If the player is connecting locally or their IP matches a specified IP, use the local host IP and port
            String formattedPlayerIp = Ip.refactorToTrueIp(playerIp);

            if (Ip.isLocal(formattedPlayerIp, serverConfig.hostLocalIp)) { // local
                linkToSend = "http://" + serverConfig.hostLocalIp;
            } else { // Otherwise, use the public host IP and port
                linkToSend = "http://" + serverConfig.hostIp;
            }
        }

        if (!serverConfig.reverseProxy) {
            // add port to link
            linkToSend += ":" + serverConfig.hostPort;
        }

        LOGGER.info("Sending {} modpack link: {}", playerName, linkToSend);

        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeString(linkToSend, 32767);

        return outBuf;
    }


    public static boolean isClientVersionHigher(String clientResponse) {
        String clientVersion = clientResponse.substring(0, clientResponse.indexOf("-"));
        boolean isClientVersionHigher = false;

        if (!clientVersion.equals(AM_VERSION)) {
            String[] clientVersionComponents = clientVersion.split("\\.");
            String[] serverVersionComponents = AM_VERSION.split("\\.");

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
