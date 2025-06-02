package pl.skidam.automodpack.networking.packet;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import pl.skidam.automodpack.networking.PacketSender;
import pl.skidam.automodpack.networking.server.ServerLoginNetworking;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.mixin.core.ServerLoginNetworkHandlerAccessor;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class DataS2CPacket {

    public static void receive(MinecraftServer server, ServerLoginNetworkHandler handler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {
        try {
            GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();

            if (!understood) {
                return;
            }

            if (buf.readableBytes() == 0) {
                return;
            }

            String clientHasUpdate = buf.readString(Short.MAX_VALUE);

            if ("true".equals(clientHasUpdate)) { // disconnect
                LOGGER.warn("{} has not installed modpack. Certificate fingerprint to verify: {}", profile.getName(), hostServer.getCertificateFingerprint());
                Text reason = VersionedText.literal("[AutoModpack] Install/Update modpack to join");
                ClientConnection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();
                connection.send(new LoginDisconnectS2CPacket(reason));
                connection.disconnect(reason);
            } else if ("false".equals(clientHasUpdate)) {
                LOGGER.info("{} has installed whole modpack", profile.getName());
            } else {
                Text reason = VersionedText.literal("[AutoModpack] Host server error. Please contact server administrator to check the server logs!");
                ClientConnection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();
                connection.send(new LoginDisconnectS2CPacket(reason));
                connection.disconnect(reason);

                // TODO: re-write these messages to reflect better new state of implementation/configuration
                LOGGER.error("Host server error. AutoModpack host server is down or server is not configured correctly");

                if (serverConfig.bindPort == -1) {
                    LOGGER.warn("You are hosting AutoModpack host server on the minecraft port.");
                    LOGGER.warn("However client can't access it, try making `hostIp` and `hostLocalIp` blank in the server config.");
                    LOGGER.warn("If that doesn't work, follow the steps bellow.");
                    LOGGER.warn("");
                } else {
                    LOGGER.warn("Please check if AutoModpack host server (TCP) port '{}' is forwarded / opened correctly", serverConfig.bindPort);
                    LOGGER.warn("");
                }

                LOGGER.warn("Make sure that '{}' address and '{}' local address are correct in the config file!", serverConfig.addressToSend, serverConfig.localAddressToSend);
                LOGGER.warn("host IP should be an ip which are players outside of server network connecting to and host local IP should be an ip which are players inside of server network connecting to");
                LOGGER.warn("It can be Ip or a correctly set domain");
                LOGGER.warn("If you need, change port in config file, forward / open it and restart server");

                if (serverConfig.bindPort != serverConfig.portToSend) {
                    LOGGER.error("bindPort '{}' is different than portToSend '{}'. If you are not using reverse proxy, match them! If you do use reverse proxy, make sure it is setup correctly.", serverConfig.bindPort, serverConfig.portToSend);
                }

                LOGGER.warn("Server certificate fingerprint to verify: {}", hostServer.getCertificateFingerprint());
            }
        } catch (Exception e) {
            LOGGER.error("Error while handling DataS2CPacket", e);
        }
    }
}
