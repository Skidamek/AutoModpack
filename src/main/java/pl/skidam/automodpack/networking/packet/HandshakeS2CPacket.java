package pl.skidam.automodpack.networking.packet;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.server.*;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static pl.skidam.automodpack.networking.ModPackets.DATA;
import static pl.skidam.automodpack_core.GlobalVariables.*;

public class HandshakeS2CPacket {

    public static void receive(MinecraftServer server, ServerLoginPacketListenerImpl handler, boolean understood, FriendlyByteBuf buf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {
        Connection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();

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

//        if (!connection.isEncrypted()) {
//            LOGGER.warn("Connection is not encrypted for player: {}", playerName);
//        }

        if (!GameHelpers.isPlayerAuthorized(connection.getRemoteAddress(), profile)) {
            return;
        }

        if (!understood) {
            Common.players.put(playerName, false);
            LOGGER.warn("{} has not installed AutoModpack.", playerName);
            if (serverConfig.requireAutoModpackOnClient) {
                Component reason = VersionedText.literal("AutoModpack mod for " + LOADER_MANAGER.getPlatformType().toString().toLowerCase() + " modloader is required to play on this server!");
                connection.send(new ClientboundLoginDisconnectPacket(reason));
                connection.disconnect(reason);
            }
        } else {
            Common.players.put(playerName, true);
            GameProfile finalProfile = profile;
            loginSynchronizer.waitFor(server.submit(() -> handleHandshake(connection, finalProfile, server.getPort(), buf, sender)));
        }
    }

    public static void handleHandshake(Connection connection, GameProfile profile, int minecraftServerPort, FriendlyByteBuf buf, PacketSender packetSender) {
        try {
            LOGGER.info("{} has installed AutoModpack.", profile.getName());

            String clientResponse = buf.readUtf(Short.MAX_VALUE);
            HandshakePacket clientHandshakePacket = HandshakePacket.fromJson(clientResponse);

            boolean isAcceptedLoader = false;
            for (String loader : serverConfig.acceptedLoaders) {
                if (clientHandshakePacket.loaders.contains(loader)) {
                    isAcceptedLoader = true;
                    break;
                }
            }

            if (!isAcceptedLoader || !clientHandshakePacket.amVersion.equals(AM_VERSION)) {
                Component reason = VersionedText.literal("AutoModpack version mismatch! Install " + AM_VERSION + " version of AutoModpack mod for " + LOADER_MANAGER.getPlatformType().toString().toLowerCase() + " to play on this server!");
                if (isClientVersionHigher(clientHandshakePacket.amVersion)) {
                    reason = VersionedText.literal("You are using a more recent version of AutoModpack than the server. Please contact the server administrator to update the AutoModpack mod.");
                }
                connection.send(new ClientboundLoginDisconnectPacket(reason));
                connection.disconnect(reason);
                return;
            }

            if (!hostServer.isRunning()) {
                LOGGER.info("Host server is not running. Modpack will not be sent to {}", profile.getName());
                return;
            }

            if (modpackExecutor.isGenerating()) {
                Component reason = VersionedText.literal("AutoModapck is generating modpack. Please wait a moment and try again.");
                connection.send(new ClientboundLoginDisconnectPacket(reason));
                connection.disconnect(reason);
                return;
            }

            // now we know player is authenticated, packets are encrypted and player is whitelisted
            // regenerate unique secret
            Secrets.Secret secret = Secrets.generateSecret();
            SecretsStore.saveHostSecret(profile.getId().toString(), secret);

            String addressToSend = serverConfig.addressToSend;
            int portToSend = serverConfig.portToSend;
            boolean requiresMagic = serverConfig.bindPort == -1;

            LOGGER.info("Sending {} modpack host address: {}:{}", profile.getName(), addressToSend, portToSend);

            DataPacket dataPacket = new DataPacket(addressToSend, portToSend, serverConfig.modpackName, secret, serverConfig.requireAutoModpackOnClient, requiresMagic);
            String packetContentJson = dataPacket.toJson();

            FriendlyByteBuf outBuf = new FriendlyByteBuf(Unpooled.buffer());
            outBuf.writeUtf(packetContentJson, Short.MAX_VALUE);
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
