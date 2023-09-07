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
import org.jetbrains.annotations.NotNull;
import pl.skidam.automodpack.mixin.core.ClientConnectionAccessor;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.content.LinkPacket;
import pl.skidam.automodpack_common.config.ConfigTools;
import pl.skidam.automodpack_common.config.Jsons;
import pl.skidam.automodpack_common.utils.FileInspection;
import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.client.ModpackUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class LinkC2SPacket {
    public static CompletableFuture<PacketByteBuf> receive(MinecraftClient client, ClientLoginNetworkHandler handler, PacketByteBuf buf, Consumer<GenericFutureListener<? extends Future<? super Void>>> genericFutureListenerConsumer) {
        String serverResponse = buf.readString(32767);

        LinkPacket linkPacket = LinkPacket.fromJson(serverResponse);

        LOGGER.info("Received link packet from server! " + linkPacket.link);

        Path modpackDir = getPath(linkPacket);

        clientConfig.selectedModpack = modpackDir.getFileName().toString();
        ConfigTools.saveConfig(clientConfigFile, clientConfig);

        Jsons.ModpackContentFields serverModpackContent = ModpackUtils.getServerModpackContent(linkPacket.link);

        Boolean isUpdate = ModpackUtils.isUpdate(serverModpackContent, modpackDir);

        if (Boolean.TRUE.equals(isUpdate)) {
            // Disconnect immediately
            ((ClientConnectionAccessor) ((ClientLoginNetworkHandlerAccessor) handler).getConnection()).getChannel().disconnect();
            new ModpackUpdater().startModpackUpdate(serverModpackContent, linkPacket.link, modpackDir);
        }

        PacketByteBuf response = PacketByteBufs.create();
        response.writeString(String.valueOf(isUpdate), 32767);

        return CompletableFuture.completedFuture(response);
    }

    @NotNull
    private static Path getPath(LinkPacket linkPacket) {
        Path modpackDir = modpacksDir;

        if (linkPacket.modpackName.isEmpty() || clientConfig.installedModpacks.contains(linkPacket.modpackName)) {
            String modpackFileName = linkPacket.link.replaceFirst("(https?://)", ""); // removes https:// and http://
            
            if (FileInspection.isInValidFileName(modpackFileName)) {
                modpackFileName = FileInspection.fixFileName(modpackFileName);   
            }
            
            modpackDir = Path.of(modpackDir + File.separator + modpackFileName);
            
        } else {
            modpackDir = Path.of(modpackDir + File.separator + linkPacket.modpackName);
        }

        return modpackDir;
    }
}
