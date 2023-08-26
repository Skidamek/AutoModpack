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
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import pl.skidam.automodpack_common.GlobalVariables;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.mixin.core.ServerLoginNetworkHandlerAccessor;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class LinkS2CPacket {

    public static void receive(MinecraftServer server, ServerLoginNetworkHandler handler, boolean b, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer sync, PacketSender sender) {
        GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();

        String clientHasUpdate = buf.readString(32767);

        if ("true".equals(clientHasUpdate)) { // disconnect
            LOGGER.warn("{} has not installed modpack", profile.getName());
            Text reason = VersionedText.common.literal("[AutoModpack] Install/Update modpack to join");
            ClientConnection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();
            connection.send(new LoginDisconnectS2CPacket(reason));
            connection.disconnect(reason);
        } else if ("false".equals(clientHasUpdate)) {
            LOGGER.info("{} has installed whole modpack", profile.getName());
        } else {
            Text reason = VersionedText.common.literal("[AutoModpack] Host server error. Please contact server administrator to check the server logs!");
            ClientConnection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();
            connection.send(new LoginDisconnectS2CPacket(reason));
            connection.disconnect(reason);

            LOGGER.error("Host server error. AutoModpack host server is down or server is not configured correctly");
            LOGGER.warn("Please check if AutoModpack host server (TCP) port '{}' is forwarded / opened correctly", GlobalVariables.serverConfig.hostPort);
            LOGGER.warn("If so make sure that host IP '{}' and host local IP '{}' are correct in the config file!", GlobalVariables.serverConfig.hostIp, GlobalVariables.serverConfig.hostLocalIp);
            LOGGER.warn("host IP should be an ip which are players outside of server network connecting to and host local IP should be an ip which are players inside of server network connecting to");
            LOGGER.warn("It can be Ip or a correctly set domain");
            LOGGER.warn("If you need, change port in config file, forward / open it and restart server");

            if (serverConfig.reverseProxy) {
                LOGGER.error("Turn off reverseProxy in config, if you don't actually use it!");
            }
        }
    }
}
