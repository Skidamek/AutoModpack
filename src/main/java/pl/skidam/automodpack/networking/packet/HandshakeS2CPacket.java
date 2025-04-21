package pl.skidam.automodpack.networking.packet;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import net.minecraft.server.*;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.init.Common;
import pl.skidam.automodpack.mixin.core.ServerLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.modpack.GameHelpers;
import pl.skidam.automodpack.networking.content.DataPacket;
import pl.skidam.automodpack.networking.content.HandshakePacket;
import pl.skidam.automodpack.networking.PacketSender;
import pl.skidam.automodpack.networking.server.ServerLoginNetworking;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.utils.AddressHelpers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static pl.skidam.automodpack.networking.ModPackets.DATA;
import static pl.skidam.automodpack_core.GlobalVariables.*;

public class HandshakeS2CPacket {

    public static void receive(MinecraftServer server, ServerLoginNetworkHandler handler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {
        ClientConnection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();

        GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();
        String playerName = profile.getName();

        if (playerName == null) {
            throw new IllegalStateException("Player name is null");
        }

        if (profile.getId() == null) {
//            if (server.isOnlineMode()) { This may happen with mods like 'easyauth', its possible to have an offline mode player join an online server
//                throw new IllegalStateException("Player: " + playerName + " doesn't have UUID");
//            }

            // Generate profile with offline uuid
            UUID offlineUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
            profile = new GameProfile(offlineUUID, playerName);
        }

        if (!connection.isEncrypted()) {
            LOGGER.warn("Connection is not encrypted for player: {}", playerName);
        }

        if (!GameHelpers.isPlayerAuthorized(connection.getAddress(), profile)) {
            return;
        }

        if (!understood) {
            Common.players.put(playerName, false);
            LOGGER.warn("{} has not installed AutoModpack.", playerName);
            if (serverConfig.requireAutoModpackOnClient) {
                Text reason = VersionedText.literal("AutoModpack mod for " + LOADER_MANAGER.getPlatformType().toString().toLowerCase() + " modloader is required to play on this server!");
                connection.send(new LoginDisconnectS2CPacket(reason));
                connection.disconnect(reason);
            }
        } else {
            Common.players.put(playerName, true);
            GameProfile finalProfile = profile;
            loginSynchronizer.waitFor(server.submit(() -> handleHandshake(connection, finalProfile, server.getServerPort(), buf, sender)));
        }
    }

    public static void handleHandshake(ClientConnection connection, GameProfile profile, int minecraftServerPort, PacketByteBuf buf, PacketSender packetSender) {
        try {
            LOGGER.info("{} has installed AutoModpack.", profile.getName());

            String clientResponse = buf.readString(Short.MAX_VALUE);
            HandshakePacket clientHandshakePacket = HandshakePacket.fromJson(clientResponse);

            boolean isAcceptedLoader = false;
            for (String loader : serverConfig.acceptedLoaders) {
                if (clientHandshakePacket.loaders.contains(loader)) {
                    isAcceptedLoader = true;
                    break;
                }
            }

            if (!isAcceptedLoader || !clientHandshakePacket.amVersion.equals(AM_VERSION)) {
                Text reason = VersionedText.literal("AutoModpack version mismatch! Install " + AM_VERSION + " version of AutoModpack mod for " + LOADER_MANAGER.getPlatformType().toString().toLowerCase() + " to play on this server!");
                if (isClientVersionHigher(clientHandshakePacket.amVersion)) {
                    reason = VersionedText.literal("You are using a more recent version of AutoModpack than the server. Please contact the server administrator to update the AutoModpack mod.");
                }
                connection.send(new LoginDisconnectS2CPacket(reason));
                connection.disconnect(reason);
                return;
            }

            if (!hostServer.isRunning()) {
                LOGGER.info("Host server is not running. Modpack will not be sent to {}", profile.getName());
                return;
            }

            if (modpack.isGenerating()) {
                Text reason = VersionedText.literal("AutoModapck is generating modpack. Please wait a moment and try again.");
                connection.send(new LoginDisconnectS2CPacket(reason));
                connection.disconnect(reason);
                return;
            }

            String playerAddress = connection.getAddress().toString();
            String addressToSend;

            // If the player is connecting locally, use the local host IP
            if (AddressHelpers.isLocal(playerAddress)) {
                addressToSend = serverConfig.hostLocalIp;
            } else {
                addressToSend = serverConfig.hostIp;
            }

            // now we know player is authenticated, packets are encrypted and player is whitelisted
            // regenerate unique secret
            Secrets.Secret secret = Secrets.generateSecret();
            SecretsStore.saveHostSecret(profile.getId().toString(), secret);

            String fingerprint = serverConfig.fingerprint;
            if (fingerprint != null && !fingerprint.isBlank()) {
                fingerprint = hostServer.getCertificateFingerprint();
            }

            if (fingerprint == null || fingerprint.isBlank()) {
                throw new IllegalStateException("Fingerprint is null or blank");
            }

            // We send empty string if hostIp/hostLocalIp is not specified in server config. Client will use ip by which it connected to the server in first place.
            DataPacket dataPacket = new DataPacket(addressToSend, null, serverConfig.modpackName, secret, fingerprint, serverConfig.requireAutoModpackOnClient);

            if (serverConfig.reverseProxy) {
                // With reverse proxy we dont append port to the link, it should be already included in the link
                // But we need to check if the port is set in the config, since that's where modpack is actually hosted
                if (serverConfig.hostPort == -1 && !serverConfig.hostModpackOnMinecraftPort) {
                    LOGGER.error("Reverse proxy is enabled but host port is not set in config! Please set it manually.");
                }

                LOGGER.info("Sending {} modpack url: {}", profile.getName(), addressToSend);
            } else { // Append server port
                int portToSend;
                if (serverConfig.hostModpackOnMinecraftPort) {
                    portToSend = minecraftServerPort;
                } else {
                    portToSend = serverConfig.hostPort;

                    if (serverConfig.hostPort == -1) {
                        LOGGER.error("Host port is not set in config! Please set it manually.");
                    }
                }

                if (!addressToSend.isBlank()) {
                    LOGGER.info("Sending {} modpack url: {}:{}", profile.getName(), addressToSend, portToSend);
                }
                dataPacket = new DataPacket(addressToSend, portToSend, serverConfig.modpackName, secret, fingerprint, serverConfig.requireAutoModpackOnClient);
            }

            String packetContentJson = dataPacket.toJson();

            PacketByteBuf outBuf = new PacketByteBuf(Unpooled.buffer());
            outBuf.writeString(packetContentJson, Short.MAX_VALUE);
            packetSender.sendPacket(DATA, outBuf);
        } catch (Exception e) {
            LOGGER.error("Error while handling handshake for {}", profile.getName(), e);
        }
    }


    public static boolean isClientVersionHigher(String clientVersion) {

        String versionPattern = "\\d+\\.\\d+\\.\\d+";
        if (!clientVersion.matches(versionPattern)) {
            return false;
        }

        if (!clientVersion.equals(AM_VERSION)) {
            String[] clientVersionComponents = clientVersion.split("\\.");
            String[] serverVersionComponents = AM_VERSION.split("\\.");

            for (int i = 0, n = clientVersionComponents.length; i < n; i++) {
                if (clientVersionComponents[i].compareTo(serverVersionComponents[i]) > 0) {
                    return true;
                }
            }
        }

        return false;
    }
}
