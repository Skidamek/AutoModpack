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

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.loaders.Loader;
import pl.skidam.automodpack.mixin.ServerLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.packet.LinkC2SPacket;
import pl.skidam.automodpack.networking.packet.LinkS2CPacket;
import pl.skidam.automodpack.networking.packet.LoginC2SPacket;
import pl.skidam.automodpack.networking.packet.LoginS2CPacket;
import pl.skidam.automodpack.utils.Wait;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;

import static pl.skidam.automodpack.StaticVariables.*;
import static pl.skidam.automodpack.StaticVariables.VERSION;
import static pl.skidam.automodpack.networking.ModPackets.HANDSHAKE;
import static pl.skidam.automodpack.networking.ModPackets.LINK;

public class ModPacketsImpl {

    // UUID, acceptLogin
    public static List<UUID> acceptLogin = new ArrayList<>();

    public static void registerC2SPackets() {
        ClientLoginNetworking.registerGlobalReceiver(HANDSHAKE, LoginC2SPacket::receive);
        ClientLoginNetworking.registerGlobalReceiver(LINK, LinkC2SPacket::receive);
    }

    public static void registerS2CPackets() {
        ServerLoginNetworking.registerGlobalReceiver(HANDSHAKE, LoginS2CPacket::receive);
        ServerLoginNetworking.registerGlobalReceiver(LINK, LinkS2CPacket::receive);

        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, sync) -> {

            GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();
            UUID uniqueId = profile.getId();

            FutureTask<?> future = new FutureTask<>(() -> {
                for (int i = 0; i <= 300; i++) {
                    new Wait(50);

                    if (acceptLogin.contains(uniqueId)) {
                        acceptLogin.remove(uniqueId);
                        break;
                    }

                    if (i == 300) {
                        LOGGER.error("Timeout login for " + profile.getName() + " (" + uniqueId.toString()  + ")");
                        Text reason = VersionedText.common.literal("AutoModpack - timeout");
                        ((ServerLoginNetworkHandlerAccessor) handler).getConnection().send(new LoginDisconnectS2CPacket(reason));
                        ((ServerLoginNetworkHandlerAccessor) handler).getConnection().disconnect(reason);
                    }
                }

                return null;
            });

            // Execute the task on a worker thread as not to block the server thread
            Util.getMainWorkerExecutor().execute(future);

            PacketByteBuf buf = PacketByteBufs.create();
            String correctResponse = VERSION + "-" + Loader.getPlatformType().toString().toLowerCase();
            if (serverConfig.allowFabricQuiltPlayers) {
                correctResponse = VERSION + "-" + "fabric&quilt";
            }
            buf.writeString(correctResponse);
            sender.sendPacket(HANDSHAKE, buf);

            sync.waitFor(future);
        });
    }
}