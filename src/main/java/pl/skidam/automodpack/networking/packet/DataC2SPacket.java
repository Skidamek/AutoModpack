package pl.skidam.automodpack.networking.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import pl.skidam.automodpack.mixin.core.ClientConnectionAccessor;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.content.DataPacket;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.config.ConfigTools.GSON;

public class DataC2SPacket {
    public static CompletableFuture<PacketByteBuf> receive(MinecraftClient minecraftClient, ClientLoginNetworkHandler handler, PacketByteBuf buf) {
        try {
            String serverResponse = buf.readString(Short.MAX_VALUE);

            DataPacket dataPacket = DataPacket.fromJson(serverResponse);
            String packetAddress = dataPacket.address;
            Integer packetPort = dataPacket.port;
            String modpackName = dataPacket.modpackName;
            Secrets.Secret secret = dataPacket.secret;
            boolean modRequired = dataPacket.modRequired;

            if (modRequired) {
                // TODO set screen to refreshed danger screen which will ask user to install modpack with two options
                // 1. Disconnect and install modpack
                // 2. Dont disconnect and join server
            }

            InetSocketAddress serverAddress = (InetSocketAddress) ((ClientLoginNetworkHandlerAccessor) handler).getConnection().getAddress();
            InetSocketAddress modpackAddress = serverAddress;

            if (packetAddress.isBlank()) {
                LOGGER.info("Address from connected server: {}:{}", modpackAddress.getAddress().getHostAddress(), modpackAddress.getPort());
            } else if (packetPort != null) {
                modpackAddress = new InetSocketAddress(packetAddress, packetPort);
                LOGGER.info("Received address packet from server! {}:{}", packetAddress, packetPort);
            } else {
                var portIndex = packetAddress.lastIndexOf(':');
                var port = portIndex == -1 ? 0 : Integer.parseInt(packetAddress.substring(portIndex + 1));
                var addressString = portIndex == -1 ? packetAddress : packetAddress.substring(0, portIndex);
                modpackAddress = new InetSocketAddress(addressString, port);
                LOGGER.info("Received address packet from server! {} Attached port: {}", addressString, port);
            }

            Boolean needsDisconnecting = null;
            PacketByteBuf response = new PacketByteBuf(Unpooled.buffer());

            Path modpackDir = ModpackUtils.getModpackPath(modpackAddress, modpackName);
            Jsons.ModpackAddresses modpackAddresses = new Jsons.ModpackAddresses(modpackAddress, serverAddress);
            var optionalServerModpackContent = ModpackUtils.requestServerModpackContent(modpackAddresses, secret, true);

            if (optionalServerModpackContent.isPresent()) {
                boolean update = ModpackUtils.isUpdate(optionalServerModpackContent.get(), modpackDir);

                if (update) {
                    disconnectImmediately(handler);
                    new ModpackUpdater().prepareUpdate(optionalServerModpackContent.get(), address, secret);
                    needsDisconnecting = true;
                } else {
                    boolean selectedModpackChanged = ModpackUtils.selectModpack(modpackDir, modpackAddresses, Set.of());

                    // save latest modpack content
                    var modpackContentFile = modpackDir.resolve(hostModpackContentFile.getFileName());
                    if (Files.exists(modpackContentFile)) {
                        Files.writeString(modpackContentFile, GSON.toJson(optionalServerModpackContent.get()));
                    }

                    if (selectedModpackChanged) {
                        SecretsStore.saveClientSecret(clientConfig.selectedModpack, secret);
                        disconnectImmediately(handler);
                        new ReLauncher(modpackDir, UpdateType.SELECT, null).restart(false);
                        needsDisconnecting = true;
                    } else {
                        needsDisconnecting = false;
                    }
                }
            } else if (ModpackUtils.canConnectModpackHost(modpackAddresses)) { // Can't download modpack because e.g. certificate is not verified but it can connect to the modpack host
                needsDisconnecting = true;
            }

            if (clientConfig.selectedModpack != null && !clientConfig.selectedModpack.isBlank()) {
                SecretsStore.saveClientSecret(clientConfig.selectedModpack, secret);
            }

            response.writeString(String.valueOf(needsDisconnecting), Short.MAX_VALUE);

            return CompletableFuture.completedFuture(response);
        } catch (Exception e) {
            LOGGER.error("Error while handling data packet", e);
            PacketByteBuf response = new PacketByteBuf(Unpooled.buffer());
            response.writeString("null", Short.MAX_VALUE);
            return CompletableFuture.completedFuture(new PacketByteBuf(Unpooled.buffer()));
        }
    }

    private static void disconnectImmediately(ClientLoginNetworkHandler clientLoginNetworkHandler) {
        ((ClientConnectionAccessor) ((ClientLoginNetworkHandlerAccessor) clientLoginNetworkHandler).getConnection()).getChannel().disconnect();
    }
}
