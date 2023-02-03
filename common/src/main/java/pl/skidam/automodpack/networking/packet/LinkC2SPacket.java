package pl.skidam.automodpack.networking.packet;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.ReLauncher;
import pl.skidam.automodpack.client.ModpackCheck;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.ScreenTools;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.utils.Wait;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static pl.skidam.automodpack.AutoModpack.modpacksDir;
import static pl.skidam.automodpack.networking.ModPackets.LINK;

public class LinkC2SPacket {
    public static CompletableFuture<PacketByteBuf> receive(MinecraftClient client, ClientLoginNetworkHandler handler, PacketByteBuf buf, Consumer<GenericFutureListener<? extends Future<? super Void>>> genericFutureListenerConsumer) {
        String link = buf.readString();
        AutoModpack.LOGGER.warn("Received link packet from server! " + link);
        AutoModpack.ClientLink = link;

        String modpackFileName = link.replaceFirst("(https?://)", ""); // removes https:// and http://
        modpackFileName = modpackFileName.replace(":", "-"); // replaces : with -
        File modpackDir = new File(modpacksDir + File.separator + modpackFileName);

        AutoModpack.clientConfig.selectedModpack = modpackFileName;
        ConfigTools.saveConfig(AutoModpack.clientConfigFile, AutoModpack.clientConfig);

        ModpackCheck.UpdateType updateType = ModpackCheck.isUpdate(link, modpackDir);
        boolean isLoaded = ModpackCheck.isLoaded(ModpackUpdater.getServerModpackContent(link));

        PacketByteBuf response = PacketByteBufs.create();
        response.writeBoolean(updateType == ModpackCheck.UpdateType.NONE && isLoaded);

        CompletableFuture.runAsync(() -> {
            if (updateType == ModpackCheck.UpdateType.DELETE) {
                new ReLauncher.Restart(modpackDir);
            } else if (updateType == ModpackCheck.UpdateType.FULL) {
                new ModpackUpdater(link, modpackDir, true);
            } else if (!isLoaded) {
                new ReLauncher.Restart(modpackDir);
            }
        });

        return CompletableFuture.completedFuture(response);
    }

    public static void receive(MinecraftClient client, ClientPlayNetworkHandler handler, PacketByteBuf buf, PacketSender sender) {
        String link = buf.readString();
        AutoModpack.LOGGER.warn("Received link packet from server! " + link);
        AutoModpack.ClientLink = link;

        String modpackFileName = link.replaceFirst("(https?://)", ""); // removes https:// and http://
        modpackFileName = modpackFileName.replace(":", "-"); // replaces : with -
        File modpackDir = new File(modpacksDir + File.separator + modpackFileName);

        AutoModpack.clientConfig.selectedModpack = modpackFileName;
        ConfigTools.saveConfig(AutoModpack.clientConfigFile, AutoModpack.clientConfig);

        ModpackCheck.UpdateType updateType = ModpackCheck.isUpdate(link, modpackDir);
        boolean isLoaded = ModpackCheck.isLoaded(ModpackUpdater.getServerModpackContent(link));

        PacketByteBuf response = PacketByteBufs.create();
        response.writeBoolean(updateType == ModpackCheck.UpdateType.NONE && isLoaded);

        sender.sendPacket(LINK, response);

        if (updateType == ModpackCheck.UpdateType.DELETE || updateType == ModpackCheck.UpdateType.FULL || !isLoaded) {
            // disconnect from server
            if (client.world != null) {
                MinecraftClient.getInstance().execute(() -> {
                    client.world.disconnect();
                    client.disconnect();
                });
            } else {
                MinecraftClient.getInstance().execute(client::disconnect);
            }

            CompletableFuture.runAsync(() -> {
                // wait until client got disconnected
                while (ScreenTools.getScreen() == null) {
                    new Wait(50);
                }

                if (updateType == ModpackCheck.UpdateType.DELETE) {
                    new ReLauncher.Restart(modpackDir);
                } else if (updateType == ModpackCheck.UpdateType.FULL) {
                    new ModpackUpdater(link, modpackDir, true);
                } else { // !isLoaded
                    new ReLauncher.Restart(modpackDir);
                }
            });
        }
    }
}
