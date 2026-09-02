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

			if (buf.readableBytes() == 0) return;

			LoginUpdateResponse clientResponse = LoginUpdateResponse.fromWire(buf.readUtf(Short.MAX_VALUE));

			if (clientResponse == LoginUpdateResponse.UPDATE_REQUIRED) { // disconnect
				String fingerprint = hostServer.getCertificateFingerprint();
				if (fingerprint == null) {
					LOGGER.warn("{} has not installed modpack", GameHelpers.getPlayerName(profile));
				} else {
					LOGGER.warn("{} has not installed modpack. Certificate fingerprint: {}", GameHelpers.getPlayerName(profile), fingerprint);
				}
				Component reason = VersionedText.literal("[AutoModpack] Install/Update modpack to join");
				Connection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();
				connection.send(new ClientboundLoginDisconnectPacket(reason));
				connection.disconnect(reason);
			} else if (clientResponse == LoginUpdateResponse.CONTINUE) {
				LOGGER.info("{} has installed whole modpack", GameHelpers.getPlayerName(profile));
			} else {
				Component reason = VersionedText.literal("[AutoModpack] Host server error. Please contact server administrator to check the server logs!");
				Connection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();
				connection.send(new ClientboundLoginDisconnectPacket(reason));
				connection.disconnect(reason);

				LOGGER.error("AutoModpack connection failed. Check the advertised endpoint and its configured connection mode.");

				if (!serverConfig.modpackHost) {
					LOGGER.warn("Built-in modpack hosting is disabled; the advertised endpoint must be handled externally.");
				} else if (serverConfig.connectionMode == ModpackConnectionMode.HOLEPUNCH) {
					LOGGER.warn("HOLEPUNCH expects a marked Minecraft Login connection; bindPort is not used.");
				} else if (serverConfig.connectionMode == ModpackConnectionMode.MAGIC && serverConfig.bindPort == -1) {
					LOGGER.warn("MAGIC expects AMMH/AMOK routing on the Minecraft port.");
				} else if (serverConfig.connectionMode == ModpackConnectionMode.MAGIC) {
					LOGGER.warn("MAGIC expects AMMH/AMOK before TLS on dedicated port '{}'.", serverConfig.bindPort);
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
			LOGGER.error("Error while handling DataS2CPacket", e);
		}
	}
}
