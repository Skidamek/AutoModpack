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

//#if FABRICLIKE

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

import java.util.UUID;

import static pl.skidam.automodpack.GlobalVariables.*;
import static pl.skidam.automodpack.networking.ModPackets.LINK;
import static pl.skidam.automodpack.networking.ModPacketsImpl.acceptLogin;

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
        UUID uniqueId = profile.getId();
        String playerName = profile.getName();

        String correctResponse = VERSION + "-" + Loader.getPlatformType().toString().toLowerCase();

        if (!understood) {
            LOGGER.warn("{} has not installed AutoModpack.", playerName);
            if (serverConfig.optionalModpack) {
                acceptLogin.add(uniqueId);
            } else {
                Text reason = VersionedText.common.literal("AutoModpack mod for " + Loader.getPlatformType().toString().toLowerCase() + " modloader is required to play on this server!");
                acceptLogin.add(uniqueId);
                connection.send(new LoginDisconnectS2CPacket(reason));
                connection.disconnect(reason);
            }
            return null;
        } else {

            LOGGER.info("{} has installed AutoModpack.", playerName);

            String clientResponse = buf.readString();
            boolean isClientVersionHigher = isClientVersionHigher(clientResponse);

            if (!clientResponse.equals(correctResponse)) {
                if (!serverConfig.allowFabricQuiltPlayers && !clientResponse.startsWith(VERSION)) {
                    Text reason = VersionedText.common.literal("AutoModpack version mismatch! Install " + VERSION + " version of AutoModpack mod for " + Loader.getPlatformType().toString().toLowerCase() + " to play on this server!");
                    if (isClientVersionHigher) {
                        reason = VersionedText.common.literal("You are using a more recent version of AutoModpack than the server. Please contact the server administrator to update the AutoModpack mod.");
                    }
                    acceptLogin.add(uniqueId);
                    connection.send(new LoginDisconnectS2CPacket(reason));
                    connection.disconnect(reason);
                    return null;
                } else if (clientResponse.startsWith(VERSION)) {
                    Text reason = VersionedText.common.literal("AutoModpack version mismatch! Install " + VERSION + " version of AutoModpack mod for " + Loader.getPlatformType().toString().toLowerCase() + " to play on this server!");
                    if (isClientVersionHigher) {
                        reason = VersionedText.common.literal("You are using a more recent version of AutoModpack than the server. Please contact the server administrator to update the AutoModpack mod.");
                    }
                    acceptLogin.add(uniqueId);
                    connection.send(new LoginDisconnectS2CPacket(reason));
                    connection.disconnect(reason);
                    return null;
                }
            }
        }

        acceptLogin.add(uniqueId);

        if (!HttpServer.isRunning() && serverConfig.externalModpackHostLink.equals("")) {
            return null;
        }

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
            if (!linkToSend.startsWith("http://") && !linkToSend.startsWith("https://")) {
                linkToSend = "http://" + linkToSend;
            }
            if (!serverConfig.reverseProxy) {
                // add port to link
                linkToSend += ":" + serverConfig.hostPort;
            }
            LOGGER.info("Sending external modpack host link: " + linkToSend);
        } else {
            // If the player is connecting locally or their IP matches a specified IP, use the local host IP and port
            if (playerIp.startsWith("/127.0.0.1") || playerIp.startsWith("/[0:0:0:0:") || playerIp.startsWith("/" + serverConfig.hostLocalIp) || playerIp.startsWith("/192.168.")) { // local
                linkToSend = "http://" + serverConfig.hostLocalIp + ":" + serverConfig.hostPort;
            } else if (!HostNetwork.equals("") && playerIp.startsWith("/" + HostNetwork)) { // local
                linkToSend = "http://" + serverConfig.hostLocalIp + ":" + serverConfig.hostPort;
            } else { // Otherwise, use the public host IP and port
                linkToSend = "http://" + serverConfig.hostIp + ":" + serverConfig.hostPort;
            }
        }

        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeString(linkToSend);

        return outBuf;
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

//#endif