package pl.skidam.automodpack.networking.packet;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.ModpackUtils;
import pl.skidam.automodpack.client.ScreenTools;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.utils.Wait;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static pl.skidam.automodpack.StaticVariables.*;
import static pl.skidam.automodpack.networking.ModPackets.LINK;

public class LinkC2SPacket {
    public static CompletableFuture<PacketByteBuf> receive(MinecraftClient client, ClientLoginNetworkHandler handler, PacketByteBuf buf, Consumer<GenericFutureListener<? extends Future<? super Void>>> genericFutureListenerConsumer) {
        String link = buf.readString();
        LOGGER.info("Received link packet from server! " + link);
        ClientLink = link;

        String modpackFileName = link.replaceFirst("(https?://)", ""); // removes https:// and http://
        modpackFileName = modpackFileName.replace(":", "-"); // replaces : with -
        File modpackDir = new File(modpacksDir + File.separator + modpackFileName);

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
