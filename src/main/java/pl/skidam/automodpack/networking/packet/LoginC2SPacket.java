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
import pl.skidam.automodpack_core.Loader;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class LoginC2SPacket {

    public static CompletableFuture<PacketByteBuf> receive(MinecraftClient client, ClientLoginNetworkHandler handler, PacketByteBuf buf, Consumer<GenericFutureListener<? extends Future<? super Void>>> genericFutureListenerConsumer) {
        // Client
        String serverResponse = buf.readString(32767);

        String loader = new Loader().getPlatformType().toString().toLowerCase();

        String correctResponse = AM_VERSION + "-" + loader;

        PacketByteBuf outBuf = PacketByteBufs.create();
        outBuf.writeString(correctResponse, 32767);

        if (serverResponse.startsWith(AM_VERSION)) {
            if (serverResponse.equals(correctResponse) || serverResponse.contains(loader)) {
                LOGGER.info("Versions match " + serverResponse);
            } else {
                LOGGER.error("Versions mismatch " + serverResponse);
            }
        } else {
            LOGGER.error("Server version error?! " + serverResponse);
        }

        return CompletableFuture.completedFuture(outBuf);
    }
}
