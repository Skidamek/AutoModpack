package pl.skidam.automodpack.networking.packet;

import static pl.skidam.automodpack.networking.ModPackets.DATA;
import static pl.skidam.automodpack_core.Constants.*;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

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
import pl.skidam.automodpack.networking.PacketSender;
import pl.skidam.automodpack.networking.content.DataPacket;
import pl.skidam.automodpack.networking.content.HandshakePacket;
import pl.skidam.automodpack.networking.server.ServerLoginNetworking;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.ConfigUtils;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.protocol.ServerHolepunchBridge;

public class HandshakeS2CPacket {

	public static void receive(MinecraftServer server, ServerLoginPacketListenerImpl handler, boolean understood, FriendlyByteBuf buf,
			ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {
		loginSynchronizer.waitFor(server.submit(() -> handlePacket(handler, understood, buf, sender)));
	}

	private static void handlePacket(ServerLoginPacketListenerImpl handler, boolean understood, FriendlyByteBuf buf, PacketSender sender) {
		try {
			processHandshake(handler, understood, buf, sender);
		} catch (Exception e) {
			// Fail closed: this check gates requireModpack enforcement, so an error here must never admit the player.
			LOGGER.error("Failed to process the AutoModpack handshake", e);
			Connection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();
			Component reason = VersionedText.literal("[AutoModpack] The server failed to process your handshake. Ask the server administrator to check the server log.");
			connection.send(new ClientboundLoginDisconnectPacket(reason));
			connection.disconnect(reason);
		}
	}

	private static void processHandshake(ServerLoginPacketListenerImpl handler, boolean understood, FriendlyByteBuf buf, PacketSender sender) throws Exception {
		Connection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();

		GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();
		String playerName = GameHelpers.getPlayerName(profile);

		if (playerName == null) throw new IllegalStateException("Player name is null");

		if (GameHelpers.getPlayerUUID(profile) == null) {
			// May happen with mods like 'easyauth': an offline-mode player can join an online server, so a missing UUID is not an error here.
			UUID offlineUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
			profile = new GameProfile(offlineUUID, playerName);
		}

		if (!understood) {
			Common.players.put(playerName, false);
			LOGGER.warn("{} has not installed AutoModpack.", playerName);
			if (serverConfig.requireModpack) {
				Component reason = VersionedText.literal(serverConfig.nagMessage);
				connection.send(new ClientboundLoginDisconnectPacket(reason));
				connection.disconnect(reason);
				return;
			}
		}

		if (!GameHelpers.isPlayerAuthorized(connection.getRemoteAddress(), GameHelpers.getPlayerUUID(profile), playerName)) {
			Component reason = VersionedText.literal("You are not authorized to join this server!");
			connection.send(new ClientboundLoginDisconnectPacket(reason));
			connection.disconnect(reason);
			return;
		}

		if (!understood) return;

		Common.players.put(playerName, true);
		handleHandshake(connection, profile, buf, sender);
	}

	private static void handleHandshake(Connection connection, GameProfile profile, FriendlyByteBuf buf, PacketSender sender) {
		try {
			LOGGER.info("{} has installed AutoModpack.", GameHelpers.getPlayerName(profile));

			String clientResponse = buf.readUtf(Short.MAX_VALUE);
			HandshakePacket clientHandshakePacket = HandshakePacket.fromJson(clientResponse);

			boolean isAcceptedLoader = acceptedLoader(clientHandshakePacket);

			if (!isAcceptedLoader) {
				Component reason = VersionedText.literal("This server does not accept your mod loader. Join with "
						+ LOADER_MANAGER.getPlatformType().toString().toLowerCase(Locale.ROOT) + ".");
				connection.send(new ClientboundLoginDisconnectPacket(reason));
				connection.disconnect(reason);
				return;
			}

			if (!clientHandshakePacket.amVersion.equals(AM_VERSION)) {
				Component reason = VersionedText.literal("AutoModpack version mismatch! Install " + AM_VERSION + " version of AutoModpack mod for "
						+ LOADER_MANAGER.getPlatformType().toString().toLowerCase(Locale.ROOT) + " to play on this server!");
				if (isClientVersionHigher(clientHandshakePacket.amVersion)) {
					reason = VersionedText.literal(
							"You are using a more recent version of AutoModpack than the server. Please contact the server administrator to update the AutoModpack mod.");
				}
				connection.send(new ClientboundLoginDisconnectPacket(reason));
				connection.disconnect(reason);
				return;
			}

			if (modpackExecutor.isGenerating()) {
				Component reason = VersionedText.literal("AutoModpack is generating modpack. Please wait a moment and try again.");
				connection.send(new ClientboundLoginDisconnectPacket(reason));
				connection.disconnect(reason);
				return;
			}

			// Advertising a HOLEPUNCH endpoint while the holepunch bridge never registered would send
			// clients into a vanilla login that swallows their holepunch connection with a cryptic
			// error; reject them here where an honest reason can be given.
			if (serverConfig.connectionMode == ModpackConnectionMode.HOLEPUNCH && !ServerHolepunchBridge.isRegistered()) {
				Component reason = VersionedText.literal("AutoModpack modpack hosting is unavailable on the server. Ask the admin to check the server log and try again later.");
				LOGGER.error("Modpack hosting is not running while the connection mode is HOLEPUNCH; rejecting {} instead of advertising a dead endpoint", GameHelpers.getPlayerName(profile));
				connection.send(new ClientboundLoginDisconnectPacket(reason));
				connection.disconnect(reason);
				return;
			}

			// now we know player is authenticated, packets are encrypted and player is whitelisted
			// regenerate unique secret, bound to the exact identity the login presented
			Secrets.Secret secret = Secrets.generateSecret();
			SecretsStore.saveHostSecret(GameHelpers.getPlayerUUID(profile).toString(), secret, GameHelpers.getPlayerName(profile));

			String advertisedEndpointHost = serverConfig.advertisedEndpointHost;
			int advertisedEndpointPort = serverConfig.advertisedEndpointPort;
			LOGGER.info("Sending {} AutoModpack endpoint: {}:{} ({})", GameHelpers.getPlayerName(profile), advertisedEndpointHost, advertisedEndpointPort, serverConfig.connectionMode);

			DataPacket dataPacket = new DataPacket(advertisedEndpointHost, advertisedEndpointPort, secret, serverConfig.connectionMode, serverConfig.requireModpack);
			String packetContentJson = dataPacket.toJson();

			FriendlyByteBuf outBuf = new FriendlyByteBuf(Unpooled.buffer());
			outBuf.writeUtf(packetContentJson, Short.MAX_VALUE);
			sender.sendPacket(DATA, outBuf);
		} catch (Exception e) {
			LOGGER.error("Error while handling handshake for {}", GameHelpers.getPlayerName(profile), e);
			Component reason = VersionedText.literal("[AutoModpack] The server failed to process your handshake. Ask the server administrator to check the server log.");
			connection.send(new ClientboundLoginDisconnectPacket(reason));
			connection.disconnect(reason);
		}
	}

	private static boolean acceptedLoader(HandshakePacket clientHandshakePacket) {
		if (serverConfig.acceptedLoaders != null) for (String loader : serverConfig.acceptedLoaders) {
			if (loader == null || loader.isBlank() || knownLoader(loader)) continue;
			LOGGER.warn("Unknown accepted-loader '{}'; it will never match", loader);
		}
		if (clientHandshakePacket.loaders == null) return false;
		for (String loader : ConfigUtils.advertisedLoaders(serverConfig)) if (clientHandshakePacket.loaders.contains(loader)) return true;
		return false;
	}

	private static boolean knownLoader(String loader) {
		for (LoaderManagerService.ModPlatform type : LoaderManagerService.ModPlatform.values())
			if (type.name().toLowerCase(Locale.ROOT).equals(loader.toLowerCase(Locale.ROOT))) return true;
		return LOADER != null && LOADER.equalsIgnoreCase(loader);
	}

	private static boolean isClientVersionHigher(String clientVersion) {
		String versionPattern = "\\d+\\.\\d+\\.\\d+";
		if (!clientVersion.matches(versionPattern)) return false;

		if (!clientVersion.equals(AM_VERSION)) {
			String[] clientVersionComponents = clientVersion.split("\\.");
			String[] serverVersionComponents = AM_VERSION.split("\\.");

			for (int i = 0, n = clientVersionComponents.length; i < n; i++) {
				if (clientVersionComponents[i].compareTo(serverVersionComponents[i]) > 0) return true;
			}
		}

		return false;
	}
}
