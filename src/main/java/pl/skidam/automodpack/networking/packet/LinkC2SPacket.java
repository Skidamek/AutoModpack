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

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.ModpackUtils;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.config.Jsons;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static pl.skidam.automodpack.GlobalVariables.*;

public class LinkC2SPacket {
    public static CompletableFuture<PacketByteBuf> receive(MinecraftClient client, ClientLoginNetworkHandler handler, PacketByteBuf buf, Consumer<GenericFutureListener<? extends Future<? super Void>>> genericFutureListenerConsumer) {
        String link = buf.readString();
        LOGGER.info("Received link packet from server! " + link);
        ClientLink = link;

        String modpackFileName = link.replaceFirst("(https?://)", ""); // removes https:// and http://
        modpackFileName = modpackFileName.replace(":", "-"); // replaces : with -
        Path modpackDir = Paths.get(modpacksDir + File.separator + modpackFileName);

        clientConfig.selectedModpack = modpackFileName;
        ConfigTools.saveConfig(clientConfigFile, clientConfig);

        Jsons.ModpackContentFields serverModpackContent = ModpackUtils.getServerModpackContent(link);

        boolean isUpdate = ModpackUtils.isUpdate(serverModpackContent, modpackDir);

        PacketByteBuf response = PacketByteBufs.create();
        response.writeBoolean(isUpdate);

        CompletableFuture.runAsync(() -> {
            if (isUpdate) {
                new ModpackUpdater(serverModpackContent, link, modpackDir);
            }
        });

        return CompletableFuture.completedFuture(response);
    }
}

//#endif