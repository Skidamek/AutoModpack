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

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import pl.skidam.automodpack.networking.content.HandshakePacket;
import pl.skidam.automodpack_core.SelfUpdater;
import pl.skidam.automodpack_core.loader.LoaderManager;
import pl.skidam.automodpack_core.platforms.ModrinthAPI;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class HandshakeC2SPacket {

    public static CompletableFuture<PacketByteBuf> receive(MinecraftClient client, ClientLoginNetworkHandler handler, PacketByteBuf buf, Consumer<GenericFutureListener<? extends Future<? super Void>>> genericFutureListenerConsumer) {
        String serverResponse = buf.readString(32767);

        HandshakePacket serverHandshakePacket = HandshakePacket.fromJson(serverResponse);

        String loader = new LoaderManager().getPlatformType().toString().toLowerCase();

        PacketByteBuf outBuf = PacketByteBufs.create();
        HandshakePacket clientHandshakePacket = new HandshakePacket(List.of(loader), AM_VERSION, MC_VERSION);
        outBuf.writeString(clientHandshakePacket.toJson(), 32767);

        if (serverHandshakePacket.equals(clientHandshakePacket) || (serverHandshakePacket.loaders.contains(loader) && serverHandshakePacket.amVersion.equals(AM_VERSION))) {
            LOGGER.info("Versions match " + serverHandshakePacket.amVersion);
        } else {
            LOGGER.warn("Versions mismatch " + serverHandshakePacket.amVersion);
            LOGGER.info("Trying to change automodpack version to the version required by server...");
            updateMod(serverHandshakePacket.amVersion, serverHandshakePacket.mcVersion);
        }

        return CompletableFuture.completedFuture(outBuf);
    }

    private static void updateMod(String serverAMVersion, String serverMCVersion) {
        if (!serverMCVersion.equals(MC_VERSION)) {
            return;
        }

        ModrinthAPI automodpack = ModrinthAPI.getModSpecificVersion(SelfUpdater.automodpackID, serverAMVersion, MC_VERSION);

        if (automodpack == null) {
            LOGGER.warn("Couldn't find {} version of automodpack for minecraft {} required by server", serverAMVersion, serverAMVersion);
            return;
        }

        SelfUpdater.installModVersion(automodpack);
    }
}
