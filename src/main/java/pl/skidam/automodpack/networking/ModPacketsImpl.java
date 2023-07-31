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

package pl.skidam.automodpack.networking;

import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.PacketByteBuf;
import pl.skidam.automodpack.networking.packet.LinkC2SPacket;
import pl.skidam.automodpack.networking.packet.LinkS2CPacket;
import pl.skidam.automodpack.networking.packet.LoginC2SPacket;
import pl.skidam.automodpack.networking.packet.LoginS2CPacket;

import static pl.skidam.automodpack.GlobalVariables.*;
import static pl.skidam.automodpack.GlobalVariables.AM_VERSION;
import static pl.skidam.automodpack.networking.ModPackets.HANDSHAKE;
import static pl.skidam.automodpack.networking.ModPackets.LINK;

public class ModPacketsImpl {

    public static void registerC2SPackets() {
        ClientLoginNetworking.registerGlobalReceiver(HANDSHAKE, LoginC2SPacket::receive);
        ClientLoginNetworking.registerGlobalReceiver(LINK, LinkC2SPacket::receive);
    }

    public static void registerS2CPackets() {
        ServerLoginNetworking.registerGlobalReceiver(HANDSHAKE, LoginS2CPacket::receive);
        ServerLoginNetworking.registerGlobalReceiver(LINK, LinkS2CPacket::receive);

        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, loginSynchronizer) -> {

            loginSynchronizer.waitFor(server.submit(() -> {
                PacketByteBuf buf = PacketByteBufs.create();
                StringBuilder correctResponse = new StringBuilder(AM_VERSION + "-");

                for (String loader : serverConfig.acceptedLoaders) {
                    correctResponse.append(loader);
                    if (serverConfig.acceptedLoaders.indexOf(loader) != serverConfig.acceptedLoaders.size() - 1) {
                        correctResponse.append("&");
                    }
                }

                buf.writeString(correctResponse.toString(), 32767);
                sender.sendPacket(HANDSHAKE, buf);
            }));
        });
    }
}
