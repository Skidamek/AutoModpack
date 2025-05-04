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
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.mixin.core.ServerLoginNetworkHandlerAccessor;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class DataS2CPacket {

    public static void receive(MinecraftServer server, ServerLoginNetworkHandler handler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {
        try {
            GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();

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

                LOGGER.error("Host server error. AutoModpack host server is down or server is not configured correctly");

                if (serverConfig.hostModpackOnMinecraftPort) {
                    LOGGER.warn("You are hosting AutoModpack host server on the minecraft port.");
                    LOGGER.warn("However client can't access it, try making `hostIp` and `hostLocalIp` blank in the server config.");
                    LOGGER.warn("If that doesn't work, follow the steps bellow.");
                    LOGGER.warn("");
                } else {
                    LOGGER.warn("Please check if AutoModpack host server (TCP) port '{}' is forwarded / opened correctly", GlobalVariables.serverConfig.hostPort);
                    LOGGER.warn("");
                }

                LOGGER.warn("Make sure that host IP '{}' and host local IP '{}' are correct in the config file!", GlobalVariables.serverConfig.hostIp, GlobalVariables.serverConfig.hostLocalIp);
                LOGGER.warn("host IP should be an ip which are players outside of server network connecting to and host local IP should be an ip which are players inside of server network connecting to");
                LOGGER.warn("It can be Ip or a correctly set domain");
                LOGGER.warn("If you need, change port in config file, forward / open it and restart server");

                if (serverConfig.reverseProxy) {
                    LOGGER.error("Turn off reverseProxy in config, if you don't actually use it!");
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error while handling DataS2CPacket", e);
        }
    }
}
