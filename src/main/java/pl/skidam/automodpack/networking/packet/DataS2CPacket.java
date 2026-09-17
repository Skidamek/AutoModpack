package pl.skidam.automodpack.networking.packet;

import static pl.skidam.automodpack_core.Constants.*;

import com.mojang.authlib.GameProfile;

import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.init.Common;
import pl.skidam.automodpack.mixin.core.ServerLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.modpack.GameHelpers;
import pl.skidam.automodpack.networking.PacketSender;
import pl.skidam.automodpack.networking.content.LoginUpdateResponse;
import pl.skidam.automodpack.networking.server.ServerLoginNetworking;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

public class DataS2CPacket {

	public static void receive(MinecraftServer server, ServerLoginPacketListenerImpl handler, boolean understood, FriendlyByteBuf buf,
			ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {
		if (!understood) return;

		loginSynchronizer.waitFor(server.submit(() -> handlePacket(handler, buf)));
	}

	private static void handlePacket(ServerLoginPacketListenerImpl handler, FriendlyByteBuf buf) {
		try {
			GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();

			if (buf.readableBytes() == 0) {
				// The handshake admitted this player, so a missing verification answer is a broken or
				// tampered client: fail closed on servers that require the modpack instead of admitting it.
				String playerName = GameHelpers.getPlayerName(profile);
				Common.players.put(playerName, false);
				LOGGER.warn("{} never answered the modpack verification query.", playerName);
				if (serverConfig.requireModpack) disconnect(handler, VersionedText.literal("[AutoModpack] Install/Update modpack to join"));
				return;
			}

			LoginUpdateResponse clientResponse = LoginUpdateResponse.fromWire(buf.readUtf(Short.MAX_VALUE));

			if (clientResponse == LoginUpdateResponse.UPDATE_REQUIRED) { // disconnect
				String fingerprint = hostServer.getCertificateFingerprint();
				if (fingerprint == null) {
					LOGGER.warn("{} has not installed modpack", GameHelpers.getPlayerName(profile));
				} else {
					LOGGER.warn("{} has not installed modpack. Certificate fingerprint: {}", GameHelpers.getPlayerName(profile), fingerprint);
				}
				disconnect(handler, VersionedText.literal("[AutoModpack] Install/Update modpack to join"));
			} else if (clientResponse == LoginUpdateResponse.CONTINUE) {
				LOGGER.info("{} has installed whole modpack", GameHelpers.getPlayerName(profile));
			} else if (clientResponse == LoginUpdateResponse.CLIENT_REJECTED) {
				String fingerprint = hostServer.getCertificateFingerprint();
				if (fingerprint == null) {
					LOGGER.warn(
							"{} refused this server's certificate: it does not match the fingerprint pinned on their client. If this server's certificate did not change, their pin is stale or something intercepts their connection.",
							GameHelpers.getPlayerName(profile));
				} else {
					LOGGER.warn(
							"{} refused this server's certificate: it does not match the fingerprint pinned on their client. Current server certificate fingerprint: {}. If the certificate did not change, their pin is stale or something intercepts their connection.",
							GameHelpers.getPlayerName(profile), fingerprint);
				}
				disconnect(handler, VersionedText.literal("[AutoModpack] Your client stopped this connection: the server's certificate does not match the certificate saved for this server."));
			} else if (clientResponse == LoginUpdateResponse.CLIENT_DECLINED) {
				LOGGER.warn("{} dismissed the certificate verification prompt", GameHelpers.getPlayerName(profile));
				disconnect(handler, VersionedText.literal("[AutoModpack] Certificate verification was dismissed. Reconnect and verify the certificate to join."));
			} else if (clientResponse == LoginUpdateResponse.JOIN_CANCELLED) {
				LOGGER.info("{} cancelled the join during the optional modpack offer", GameHelpers.getPlayerName(profile));
				disconnect(handler, VersionedText.literal("[AutoModpack] Join cancelled."));
			} else {
				disconnect(handler, VersionedText.literal("[AutoModpack] Host server error. Please contact server administrator to check the server logs!"));

				LOGGER.error("AutoModpack connection failed. Check the advertised endpoint and its configured connection mode.");

				if (!serverConfig.modpackHost) {
					LOGGER.warn("Built-in modpack hosting is disabled; the advertised endpoint must be handled externally.");
				} else if (serverConfig.connectionMode == ModpackConnectionMode.HOLEPUNCH) {
					LOGGER.warn("HOLEPUNCH expects a marked Minecraft Login connection; bindPort is not used.");
				} else if (serverConfig.connectionMode == ModpackConnectionMode.MAGIC && serverConfig.bindPort == -1) {
					LOGGER.warn("MAGIC expects AMMH/AMOK routing on the Minecraft port.");
				} else if (serverConfig.connectionMode == ModpackConnectionMode.MAGIC) {
					LOGGER.warn("MAGIC expects AMMH/AMOK before TLS on dedicated port '{}'.", serverConfig.bindPort);
				} else if (serverConfig.connectionMode == ModpackConnectionMode.HTTP && serverConfig.bindPort == -1) {
					LOGGER.warn("HTTP with bindPort -1 is only advertised; the URL contract must be served externally over HTTPS.");
				} else if (serverConfig.connectionMode == ModpackConnectionMode.HTTP) {
					LOGGER.warn("HTTP expects TLS (or a TLS-terminating proxy in front) immediately on dedicated port '{}'.", serverConfig.bindPort);
				} else if (serverConfig.bindPort == -1) {
					LOGGER.warn("DIRECT with bindPort -1 starts no built-in listener; the advertised endpoint must be handled externally.");
				} else {
					LOGGER.warn("DIRECT expects TLS immediately on dedicated port '{}'.", serverConfig.bindPort);
				}

				if (serverConfig.disableInternalTLS) {
					LOGGER.warn("Internal TLS termination is disabled; clients still use TLS and a compatible terminator must forward decrypted traffic to AutoModpack.");
				}

				LOGGER.warn("Verify advertisedEndpointHost and advertisedEndpointPort, including any proxy or external routing configuration.");
				String fingerprint = hostServer.getCertificateFingerprint();
				if (fingerprint != null) LOGGER.warn("Server certificate fingerprint: {}", fingerprint);
			}
		} catch (Exception e) {
			LOGGER.error("Error while handling the modpack verification response", e);
			disconnect(handler, VersionedText.literal("[AutoModpack] Your modpack verification response was unreadable. Reconnect, and if it repeats ask the server administrator to check the server log."));
		}
	}

	private static void disconnect(ServerLoginPacketListenerImpl handler, Component reason) {
		Connection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();
		connection.send(new ClientboundLoginDisconnectPacket(reason));
		connection.disconnect(reason);
	}
}
