package pl.skidam.automodpack.networking.packet;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import pl.skidam.automodpack.modpack.GameHelpers;
import pl.skidam.automodpack.networking.ConnectionIrohTunnelRegistry;
import pl.skidam.automodpack.networking.PacketSender;
import pl.skidam.automodpack.networking.server.ServerLoginNetworking;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.mixin.core.ServerLoginNetworkHandlerAccessor;

import static pl.skidam.automodpack_core.Constants.*;

public class DataS2CPacket {

    public static void receive(MinecraftServer server, ServerLoginPacketListenerImpl handler, boolean understood, FriendlyByteBuf buf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {
        if (!understood) {
            return;
        }

        loginSynchronizer.waitFor(server.submit(() -> handlePacket(handler, buf)));
    }

    private static void handlePacket(ServerLoginPacketListenerImpl handler, FriendlyByteBuf buf) {
        try {
            GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();
            Connection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();
            ConnectionIrohTunnelRegistry.removeServer(connection);

            if (buf.readableBytes() == 0) {
                return;
            }

            String clientHasUpdate = buf.readUtf(Short.MAX_VALUE);

            if ("true".equals(clientHasUpdate)) { // disconnect
                LOGGER.warn("{} has not installed modpack. Endpoint ID: {}", GameHelpers.getPlayerName(profile), hostServer.getIrohEndpointId());
                Component reason = VersionedText.literal("[AutoModpack] Install/Update modpack to join");
                connection.send(new ClientboundLoginDisconnectPacket(reason));
                connection.disconnect(reason);
            } else if ("false".equals(clientHasUpdate)) {
                LOGGER.info("{} has installed whole modpack", GameHelpers.getPlayerName(profile));
            } else {
                Component reason = VersionedText.literal("[AutoModpack] Host server error. Please contact server administrator to check the server logs!");
                connection.send(new ClientboundLoginDisconnectPacket(reason));
                connection.disconnect(reason);

                LOGGER.error("Host server error. AutoModpack host server is down or server is not configured correctly");

                if (serverConfig.bindPort == -1) {
                    LOGGER.warn("AutoModpack raw TCP hosting is configured on the Minecraft port.");
                    LOGGER.info("If the client sent its original hostname and port, the raw TCP route is derived per-client from that login handshake.");
                } else {
                    LOGGER.warn("Please check if AutoModpack host server (TCP) port '{}' is forwarded / opened correctly", serverConfig.bindPort);
                    if (serverConfig.portToSend <= 0) {
                        LOGGER.info("No explicit portToSend is configured, so clients will use bindPort '{}' for raw TCP bootstrap.", serverConfig.bindPort);
                    }
                }

                if (serverConfig.addressToSend == null || serverConfig.addressToSend.isBlank()) {
                    LOGGER.warn("No explicit addressToSend is configured. Clients will only get a raw TCP route if their login handshake provided the original server hostname.");
                } else {
                    LOGGER.info("Configured raw TCP host override addressToSend='{}'", serverConfig.addressToSend);
                }
                LOGGER.warn("If nothing works, try changing the 'bindPort' in the config file, then forward / open it and restart server");
                LOGGER.warn("Note that some hosting providers may proxy this port internally and give you a different address and port to use. In this case, separate the given address with ':', and set the first part as 'addressToSend' and the second part as 'portToSend' in the config file.");

                if (serverConfig.bindPort != serverConfig.portToSend && serverConfig.bindPort != -1 && serverConfig.portToSend != -1) {
                    LOGGER.error("bindPort '{}' is different than portToSend '{}'. If you are not using reverse proxy, match them! If you do use reverse proxy, make sure it is setup correctly.", serverConfig.bindPort, serverConfig.portToSend);
                }

                LOGGER.warn("Server iroh endpoint ID: {}", hostServer.getIrohEndpointId());
            }
        } catch (Exception e) {
            LOGGER.error("Error while handling DataS2CPacket", e);
        }
    }
}
