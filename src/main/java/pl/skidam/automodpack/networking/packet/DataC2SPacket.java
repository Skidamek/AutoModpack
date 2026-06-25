package pl.skidam.automodpack.networking.packet;

import io.netty.buffer.Unpooled;
import pl.skidam.automodpack.mixin.core.ClientConnectionAccessor;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack.networking.content.DataPacket;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.config.ConfigTools.GSON;

public class DataC2SPacket {
    public static CompletableFuture<FriendlyByteBuf> receive(Minecraft client, ClientHandshakePacketListenerImpl handler, FriendlyByteBuf buf) {
        DataPacket dataPacket;
        try {
            String serverResponse = buf.readUtf(Short.MAX_VALUE);
            dataPacket = DataPacket.fromJson(serverResponse);
        } catch (Exception e) {
            LOGGER.error("Error parsing data packet", e);
            FriendlyByteBuf error = new FriendlyByteBuf(Unpooled.buffer());
            error.writeUtf("null", Short.MAX_VALUE);
            return CompletableFuture.completedFuture(error);
        }

        String packetAddress = dataPacket.address == null ? "" : dataPacket.address;
        int packetPort = dataPacket.port;
        String modpackName = dataPacket.modpackName == null ? "" : dataPacket.modpackName;
        Secrets.Secret secret = dataPacket.secret;
        boolean modRequired = dataPacket.modRequired;
        boolean requiresMagic = dataPacket.requiresMagic;

        if (modRequired) {
            // TODO set screen to refreshed danger screen which will ask user to install modpack with two options
            // 1. Disconnect and install modpack
            // 2. Dont disconnect and join server
        }

        InetSocketAddress serverAddress = ModPackets.getOriginalServerAddress();
        ModPackets.setOriginalServerAddress(null); // Reset for next server reconnection
        if (serverAddress == null) {
            LOGGER.error("Server address is null! Something gone very wrong! Please report this issue! https://github.com/Skidamek/AutoModpack/issues");
            return CompletableFuture.completedFuture(buildResponse(null));
        }

        Path modpackDir;
        Jsons.ModpackAddresses modpackAddresses;
        try {
            // Get actual address of the server client have connected to and format it
            InetSocketAddress connectedAddress = (InetSocketAddress) ((ClientLoginNetworkHandlerAccessor) handler).getConnection().getRemoteAddress();
            String effectiveHost;
            int effectivePort;

            // If the packet specifies a non-blank address, use it or else use address from the server client have connected to.
            // Important! Use getAddress().getHostAddress() instead of getHostString()
            // because Minecraft creates connectedAddress instance through a constructor which attempts a reverse DNS lookup
            // which resolves PTR record for the IP address and stores the resolved hostname in the hostname field.
            if (packetAddress.isBlank()) {
                var connectedInetAddress = connectedAddress.getAddress();
                effectiveHost = connectedInetAddress == null ? connectedAddress.getHostString() : connectedInetAddress.getHostAddress();
            } else {
                effectiveHost = packetAddress;
            }

            if (packetPort == -1) {
                effectivePort = connectedAddress.getPort();
            } else {
                effectivePort = packetPort;
            }

            // Construct the final modpack address
            InetSocketAddress modpackAddress = AddressHelpers.format(effectiveHost, effectivePort);

            LOGGER.info("Modpack address: {}:{} Requires to follow magic protocol: {}", modpackAddress.getHostString(), modpackAddress.getPort(), requiresMagic);

            modpackDir = ModpackUtils.getModpackPath(modpackAddress, modpackName);
            modpackAddresses = new Jsons.ModpackAddresses(modpackAddress, serverAddress, requiresMagic);
        } catch (Exception e) {
            LOGGER.error("Error preparing modpack address from data packet", e);
            return CompletableFuture.completedFuture(buildResponse(null));
        }

        return ModpackUtils.requestServerModpackContentAsync(modpackAddresses, secret, true)
                .thenApplyAsync(optionalServerModpackContent -> {
                    Boolean needsDisconnecting = null;

                    if (optionalServerModpackContent.isPresent()) {
                        ModpackUtils.UpdateCheckResult updateCheckResult = ModpackUtils.isUpdate(optionalServerModpackContent.get(), modpackDir);

                        if (updateCheckResult.requiresUpdate()) {
                            disconnectImmediately(handler);
                            new ModpackUpdater(optionalServerModpackContent.get(), modpackAddresses, secret, modpackDir).processModpackUpdate(updateCheckResult);
                            needsDisconnecting = true;
                        } else {
                            boolean selectedModpackChanged = ModpackUtils.selectModpack(modpackDir, modpackAddresses, Set.of());

                            var modpackContentFile = modpackDir.resolve(hostModpackContentFile.getFileName());
                            if (Files.exists(modpackContentFile)) {
                                try {
                                    Files.writeString(modpackContentFile, GSON.toJson(optionalServerModpackContent.get()));
                                } catch (Exception ignored) {}
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
                    } else if (ModpackUtils.canConnectModpackHost(modpackAddresses)) {
                        // Couldn't download the modpack content (e.g. certificate not verified) but the host is reachable
                        needsDisconnecting = true;
                    }

                    if (clientConfig.selectedModpack != null && !clientConfig.selectedModpack.isBlank()) {
                        SecretsStore.saveClientSecret(clientConfig.selectedModpack, secret);
                    }

                    return buildResponse(needsDisconnecting);
                })
                .exceptionally(e -> {
                    LOGGER.error("Error while handling data packet", e);
                    FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
                    response.writeUtf("null", Short.MAX_VALUE);
                    return response;
                });
    }

    private static FriendlyByteBuf buildResponse(Boolean needsDisconnecting) {
        FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
        if (needsDisconnecting != null) {
            response.writeUtf(String.valueOf(needsDisconnecting), Short.MAX_VALUE);
        } else {
            response.writeUtf("null", Short.MAX_VALUE);
        }
        return response;
    }

    private static void disconnectImmediately(ClientHandshakePacketListenerImpl clientLoginNetworkHandler) {
        var channel = ((ClientConnectionAccessor) ((ClientLoginNetworkHandlerAccessor) clientLoginNetworkHandler).getConnection()).getChannel();
        channel.disconnect();
    }
}
