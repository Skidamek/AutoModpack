package pl.skidam.automodpack.networking.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import pl.skidam.automodpack.mixin.core.ClientConnectionAccessor;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.ConnectionIrohTunnelRegistry;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack.networking.content.DataPacket;
import pl.skidam.automodpack.networking.connection.AutoModpackConnectionTransportHolder;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.ConnectionIrohDownloadClient;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.ModpackConnectionInfo;
import pl.skidam.automodpack_core.protocol.iroh.tunnel.ClientConnectionIrohTunnelSession;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.config.ConfigTools.GSON;

public class DataC2SPacket {
    public static CompletableFuture<FriendlyByteBuf> receive(Minecraft minecraft, ClientHandshakePacketListenerImpl handler, FriendlyByteBuf buf) {
        final String serverResponse;
        final InetSocketAddress serverAddress = ModPackets.getOriginalServerAddress();
        final Connection connection = ((ClientLoginNetworkHandlerAccessor) handler).getConnection();
        ModPackets.setOriginalServerAddress(null);

        try {
            serverResponse = buf.readUtf(Short.MAX_VALUE);
        } catch (Exception e) {
            LOGGER.error("Error while reading data packet", e);
            return CompletableFuture.completedFuture(emptyResponse("null"));
        }

        return CompletableFuture.supplyAsync(() -> handlePacket(handler, connection, serverAddress, serverResponse));
    }

    private static FriendlyByteBuf handlePacket(ClientHandshakePacketListenerImpl handler, Connection connection, InetSocketAddress serverAddress, String serverResponse) {
        try {
            DataPacket dataPacket = DataPacket.fromJson(serverResponse);
            DataPacket.IrohAddressBookAdvertisement iroh = dataPacket.iroh;

            String endpointId = iroh == null ? null : iroh.endpointId;
            List<String> packetDirectAddresses = iroh == null || iroh.directIpAddresses == null ? List.of() : iroh.directIpAddresses;
            String packetAddress = iroh == null || iroh.rawTcp == null ? "" : iroh.rawTcp.host;
            int packetPort = iroh == null || iroh.rawTcp == null ? -1 : iroh.rawTcp.port;
            String modpackName = dataPacket.modpackName;
            boolean modRequired = dataPacket.modRequired;
            boolean shareMinecraftConnection = iroh != null && iroh.minecraft != null;
            long tunnelSessionId = iroh == null || iroh.minecraft == null ? 0L : iroh.minecraft.tunnelSessionId;

            if (modRequired) {
                // TODO set screen to refreshed danger screen which will ask user to install modpack with two options
                // 1. Disconnect and install modpack
                // 2. Dont disconnect and join server
            }

            if (serverAddress == null) {
                LOGGER.error("Server address is null! Something gone very wrong! Please report this issue! https://github.com/Skidamek/AutoModpack/issues");
                return emptyResponse("null");
            }

            InetSocketAddress connectedAddress = serverAddress;
            if (connectedAddress == null) {
                var remoteAddress = connection.getRemoteAddress();
                if (remoteAddress instanceof InetSocketAddress inetRemoteAddress) {
                    connectedAddress = inetRemoteAddress;
                } else if (remoteAddress != null) {
                    connectedAddress = AddressHelpers.parse(remoteAddress.toString());
                }
            }
            if (connectedAddress == null) {
                LOGGER.error("Failed to resolve the connected server address from {}", connection.getRemoteAddress());
                return emptyResponse("null");
            }
            InetSocketAddress modpackAddress = null;
            if (packetAddress != null && !packetAddress.isBlank() && packetPort > 0) {
                modpackAddress = AddressHelpers.format(packetAddress, packetPort);
            }
            List<InetSocketAddress> directAddresses = new ArrayList<>();
            for (String directAddress : packetDirectAddresses) {
                try {
                    directAddresses.add(AddressHelpers.parse(directAddress));
                } catch (Exception e) {
                    LOGGER.warn("Ignoring invalid direct iroh address '{}'", directAddress, e);
                }
            }

            LOGGER.info(
                "Modpack address: {}:{} Share Minecraft connection: {}",
                modpackAddress == null ? "<none>" : modpackAddress.getHostString(),
                modpackAddress == null ? -1 : modpackAddress.getPort(),
                shareMinecraftConnection
            );

            Path modpackDir = ModpackUtils.getModpackPath(modpackAddress != null ? modpackAddress : connectedAddress, modpackName, endpointId);
            ClientConnectionIrohTunnelSession tunnelSession = null;
            if (shareMinecraftConnection) {
                if (endpointId == null || endpointId.isBlank()) {
                    LOGGER.warn("Server advertised shareMinecraftConnection without a valid iroh endpoint id");
                    shareMinecraftConnection = false;
                } else if (tunnelSessionId <= 0L) {
                    LOGGER.warn("Server advertised shareMinecraftConnection without a valid tunnel session id");
                    shareMinecraftConnection = false;
                } else {
                    try {
                        var connectionManager = ((AutoModpackConnectionTransportHolder) connection).automodpack$getConnectionManager();
                        if (connectionManager == null) {
                            LOGGER.warn("Minecraft connection transport is not installed, disabling shareMinecraftConnection");
                            shareMinecraftConnection = false;
                        } else {
                            tunnelSession = new ClientConnectionIrohTunnelSession(
                                tunnelSessionId,
                                endpointId,
                                connectionManager,
                                modpackAddress
                            );
                            tunnelSession.start();
                            ConnectionIrohTunnelRegistry.registerClient(connection, tunnelSession);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to start connection-level client iroh tunnel", e);
                        ConnectionIrohTunnelRegistry.removeClient(connection);
                        shareMinecraftConnection = false;
                        tunnelSession = null;
                    }
                }
            }
            ModpackConnectionInfo connectionInfo = new ModpackConnectionInfo(
                serverAddress,
                endpointId,
                directAddresses,
                modpackAddress,
                shareMinecraftConnection
            );
            Jsons.PersistedModpackConnection persistedConnection = connectionInfo.toPersistedModpackConnection();

            ClientConnectionIrohTunnelSession activeTunnelSession = tunnelSession;
            IntFunction<DownloadClient> fetchTunnelClientFactory = activeTunnelSession == null
                ? null
                : poolSize -> createConnectionDownloadClient(activeTunnelSession, connectionInfo, persistedConnection, poolSize, true);
            IntFunction<DownloadClient> downloadTunnelClientFactory = activeTunnelSession == null
                ? null
                : poolSize -> createConnectionDownloadClient(activeTunnelSession, connectionInfo, persistedConnection, poolSize, false);

            Boolean needsDisconnecting = null;
            var optionalServerModpackContent = ModpackUtils.requestServerModpackContent(
                connectionInfo,
                true,
                fetchTunnelClientFactory
            );

            if (optionalServerModpackContent.isPresent()) {
                ModpackUtils.UpdateCheckResult updateCheckResult = ModpackUtils.isUpdate(optionalServerModpackContent.get(), modpackDir);

                if (updateCheckResult.requiresUpdate()) {
                    if (!shareMinecraftConnection || tunnelSession == null) {
                        disconnectImmediately(handler);
                    }

                    ModpackUpdater updater = new ModpackUpdater(
                        optionalServerModpackContent.get(),
                        connectionInfo,
                        persistedConnection,
                        modpackDir,
                        downloadTunnelClientFactory
                    );
                    updater.processModpackUpdate(updateCheckResult);
                    if (shareMinecraftConnection && tunnelSession != null) {
                        LOGGER.info("Waiting for connection-level tunnel-backed modpack update to finish before replying to the server");
                        needsDisconnecting = updater.getLoginUpdateFuture().get();
                    } else {
                        needsDisconnecting = true;
                    }
                } else {
                    boolean selectedModpackChanged = ModpackUtils.selectModpack(modpackDir, persistedConnection, Set.of());

                    Path modpackContentFile = modpackDir.resolve(hostModpackContentFile.getFileName());
                    if (Files.exists(modpackContentFile)) {
                        Files.writeString(modpackContentFile, GSON.toJson(optionalServerModpackContent.get()));
                    }

                    if (selectedModpackChanged) {
                        disconnectImmediately(handler);
                        new ReLauncher(modpackDir, UpdateType.SELECT, null).restart(false);
                        needsDisconnecting = true;
                    } else {
                        needsDisconnecting = false;
                    }
                }
            } else if (ModpackUtils.canConnectModpackHost(connectionInfo)) {
                needsDisconnecting = true;
            }

            if (shareMinecraftConnection) {
                ConnectionIrohTunnelRegistry.removeClient(connection);
            }

            return emptyResponse(String.valueOf(needsDisconnecting));
        } catch (Exception e) {
            LOGGER.error("Error while handling data packet", e);
            ConnectionIrohTunnelRegistry.removeClient(connection);
            return emptyResponse("null");
        }
    }

    private static FriendlyByteBuf emptyResponse(String value) {
        FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
        response.writeUtf(value, Short.MAX_VALUE);
        return response;
    }

    private static DownloadClient createConnectionDownloadClient(
        ClientConnectionIrohTunnelSession session,
        ModpackConnectionInfo connectionInfo,
        Jsons.PersistedModpackConnection persistedConnection,
        int poolSize,
        boolean ignoredAllowAskingUser
    ) {
        try {
            return new ConnectionIrohDownloadClient(
                session,
                new ModpackConnectionInfo(
                    connectionInfo.minecraftServerAddress(),
                    connectionInfo.endpointId(),
                    connectionInfo.directIpAddresses(),
                    connectionInfo.rawTcpAddress(),
                    false
                ),
                poolSize
            );
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize connection-level iroh download client", e);
            return null;
        }
    }

    private static void disconnectImmediately(ClientHandshakePacketListenerImpl clientLoginNetworkHandler) {
        ((ClientConnectionAccessor) ((ClientLoginNetworkHandlerAccessor) clientLoginNetworkHandler).getConnection()).getChannel().disconnect();
    }
}
