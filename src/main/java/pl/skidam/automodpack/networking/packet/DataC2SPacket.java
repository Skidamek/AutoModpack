package pl.skidam.automodpack.networking.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import pl.skidam.automodpack.mixin.core.ClientConnectionAccessor;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.content.DataPacket;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class DataC2SPacket {
    public static CompletableFuture<PacketByteBuf> receive(MinecraftClient minecraftClient, ClientLoginNetworkHandler handler, PacketByteBuf buf) {
        String serverResponse = buf.readString(Short.MAX_VALUE);

        DataPacket dataPacket = DataPacket.fromJson(serverResponse);
        String link = dataPacket.link;
        boolean modRequired = dataPacket.modRequired;

        if (modRequired) {
            // TODO set screen to refreshed danger screen which will ask user to install modpack with two options
            // 1. Disconnect and install modpack
            // 2. Dont disconnect and join server
        }

        if (link.isBlank()) {
            InetSocketAddress socketAddress = (InetSocketAddress) ((ClientLoginNetworkHandlerAccessor) handler).getConnection().getAddress();
            InetAddress inetAddress = socketAddress.getAddress();
            String ipAddress = inetAddress.getHostAddress();
            int port = socketAddress.getPort();

            if (inetAddress instanceof Inet6Address) {
                ipAddress = "[" + ipAddress + "]";
            }

            link = "http://" + ipAddress + ":" + port;
            LOGGER.info("Http url from connected server: {}", link);
        } else {
            LOGGER.info("Received link packet from server! {}", link);
        }

        // TODO: dont require/hardcode this
        link = link + "/automodpack/";

        Path modpackDir = ModpackUtils.getModpackPath(link, dataPacket.modpackName);
        boolean selectedModpackChanged = ModpackUtils.selectModpack(modpackDir, link);

        Boolean needsDisconnecting = null;

        var optionalServerModpackContent = ModpackUtils.requestServerModpackContent(link);

        if (optionalServerModpackContent.isPresent()) {
            boolean update = ModpackUtils.isUpdate(optionalServerModpackContent.get(), modpackDir);

            if (update) {
                disconnectImmediately(handler);
                new ModpackUpdater().startModpackUpdate(optionalServerModpackContent.get(), link, modpackDir);
                needsDisconnecting = true;
            } else if (selectedModpackChanged) {
                disconnectImmediately(handler);
                // Its needed since newly selected modpack may not be loaded
                new ReLauncher(modpackDir, UpdateType.SELECT).restart(false);
                needsDisconnecting = true;
            } else {
                needsDisconnecting = false;
            }
        }

        PacketByteBuf response = new PacketByteBuf(Unpooled.buffer());
        response.writeString(String.valueOf(needsDisconnecting), Short.MAX_VALUE);

        return CompletableFuture.completedFuture(response);

    }

    private static void disconnectImmediately(ClientLoginNetworkHandler clientLoginNetworkHandler) {
        ((ClientConnectionAccessor) ((ClientLoginNetworkHandlerAccessor) clientLoginNetworkHandler).getConnection()).getChannel().disconnect();
    }
}
