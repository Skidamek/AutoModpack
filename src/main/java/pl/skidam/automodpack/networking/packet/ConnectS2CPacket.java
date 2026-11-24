package pl.skidam.automodpack.networking.packet;

import static pl.skidam.automodpack.networking.ModPackets.CONNECT;
import static pl.skidam.automodpack.networking.ModPackets.HANDSHAKE;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.authlib.GameProfile;

import io.netty.buffer.Unpooled;
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
import pl.skidam.automodpack.networking.content.HandshakePacket;
import pl.skidam.automodpack.networking.content.LoginUpdateResponse;
import pl.skidam.automodpack.networking.server.ServerLoginNetworking;
import pl.skidam.automodpack_core.Environment;
import pl.skidam.automodpack_core.Hosting;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.ConfigUtils;
import pl.skidam.automodpack_core.config.ServerConfigStore;
import pl.skidam.automodpack_core.config.WireJsons;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.modpack.PackAbsence;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.protocol.ServerHolepunchBridge;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

public class ConnectS2CPacket {
	private static final Logger LOGGER = LogManager.getLogger(ConnectS2CPacket.class);

	public static void sendOnReady(ServerLoginPacketListenerImpl handler, MinecraftServer server, ServerLoginNetworking.LoginSynchronizer synchronizer, PacketSender sender) {
		synchronizer.waitFor(server.submit(() -> {
			try {
				Connection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();
				GameProfile profile = resolveProfile(handler);
				String playerName = GameHelpers.getPlayerName(profile);

				if (!GameHelpers.isPlayerAuthorized(connection.getRemoteAddress(), GameHelpers.getPlayerUUID(profile), playerName)) {
					disconnect(handler, VersionedText.literal("You are not authorized to join this server!"));
					return;
				}

				warnUnknownAcceptedLoaders();
				boolean generating = Hosting.executor().isGenerating();
				boolean nothingToServeHere = nothingToServeHere();

				WireJsons.RouteFields route = null;
				Secrets.Secret secret = null;
				if (!generating && !nothingToServeHere) {
					if (ServerConfigStore.current().connectionMode == ModpackConnectionMode.HOLEPUNCH && !ServerHolepunchBridge.isRegistered()) {
						LOGGER.error("Modpack hosting is unavailable; rejecting {} instead of advertising an unreachable endpoint", playerName);
						disconnect(handler, VersionedText.literal("AutoModpack modpack hosting is unavailable on the server. Ask the admin to check the server log and try again later."));
						return;
					}
					if (ServerConfigStore.current().validateSecrets) {
						secret = Secrets.generateSecret();
						SecretsStore.saveHostSecret(GameHelpers.getPlayerUUID(profile).toString(), secret, playerName);
					}
					route = WireJsons.RouteFields.fromServerConfig(ServerConfigStore.current());
				}

				WireJsons.ConnectDataFields connectData = new WireJsons.ConnectDataFields(
						Environment.current().modVersion(),
						Environment.current().mcVersion(),
						ConfigUtils.advertisedLoaders(ServerConfigStore.current()),
						ServerConfigStore.current().requireModpack,
						generating,
						route,
						secret);

				FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
				buf.writeUtf(connectData.toJson(), Short.MAX_VALUE);
				sender.sendPacket(CONNECT, buf);
			} catch (Exception e) {
				LOGGER.error("Failed to build AutoModpack connect packet", e);
				disconnect(handler, VersionedText.literal("[AutoModpack] The server failed to process your handshake. Ask the server administrator to check the server log."));
			}
		}));
	}

	public static void receive(MinecraftServer server, ServerLoginPacketListenerImpl handler, boolean understood, FriendlyByteBuf buf,
			ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {
		loginSynchronizer.waitFor(server.submit(() -> handleConnectResponse(handler, understood, buf, sender)));
	}

	public static void receiveLegacyHandshake(MinecraftServer server, ServerLoginPacketListenerImpl handler, boolean understood, FriendlyByteBuf buf,
			ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {
		loginSynchronizer.waitFor(server.submit(() -> handleLegacyHandshakeResponse(handler, understood)));
	}

	private static void handleConnectResponse(ServerLoginPacketListenerImpl handler, boolean understood, FriendlyByteBuf buf, PacketSender sender) {
		try {
			GameProfile profile = resolveProfile(handler);
			String playerName = GameHelpers.getPlayerName(profile);

			if (!understood) {
				// Could be a vanilla client or a <v5.0.0-rc.8 AutoModpack client; send legacy HANDSHAKE so older clients trigger SelfUpdater.
				FriendlyByteBuf legacyBuf = new FriendlyByteBuf(Unpooled.buffer());
				HandshakePacket legacy = new HandshakePacket(ConfigUtils.advertisedLoaders(ServerConfigStore.current()), Environment.current().modVersion(), Environment.current().mcVersion());
				legacyBuf.writeUtf(legacy.toJson(), Short.MAX_VALUE);
				sender.sendPacket(HANDSHAKE, legacyBuf);
				return;
			}

			Common.players.put(playerName, true);

			if (buf.readableBytes() == 0) {
				Common.players.put(playerName, false);
				LOGGER.warn("{} never answered the modpack verification query.", playerName);
				if (ServerConfigStore.current().requireModpack && !nothingToServeHere()) disconnect(handler, VersionedText.literal("[AutoModpack] Install/Update modpack to join"));
				return;
			}

			LoginUpdateResponse clientResponse = LoginUpdateResponse.fromCode(buf.readByte());
			switch (clientResponse) {
				case CONTINUE -> LOGGER.debug("{} has installed whole modpack", playerName);
				case UPDATE_REQUIRED -> {
					String fingerprint = Hosting.server().getCertificateFingerprint();
					if (fingerprint == null) LOGGER.warn("{} has not installed modpack", playerName);
					else LOGGER.warn("{} has not installed modpack. Certificate fingerprint: {}", playerName, fingerprint);
					disconnect(handler, VersionedText.literal("[AutoModpack] Install/Update modpack to join"));
				}
				case CLIENT_REJECTED -> {
					String fingerprint = Hosting.server().getCertificateFingerprint();
					if (fingerprint == null) LOGGER.warn("{} refused this server's certificate: it does not match the fingerprint pinned on their client.", playerName);
					else LOGGER.warn("{} refused this server's certificate: it does not match the fingerprint pinned on their client. Current server certificate fingerprint: {}.", playerName, fingerprint);
					disconnect(handler, VersionedText.literal("[AutoModpack] Your client stopped this connection: the server's certificate does not match the certificate saved for this server."));
				}
				case CLIENT_DECLINED -> {
					LOGGER.warn("{} dismissed the certificate verification prompt", playerName);
					disconnect(handler, VersionedText.literal("[AutoModpack] Certificate verification was dismissed. Reconnect and verify the certificate to join."));
				}
				case JOIN_CANCELLED -> {
					LOGGER.info("{} cancelled the join during the optional modpack offer", playerName);
					disconnect(handler, VersionedText.literal("[AutoModpack] Join cancelled."));
				}
				case CONTRACT_MISMATCH -> disconnect(handler, VersionedText
						.literal("AutoModpack version mismatch. This server runs " + Environment.current().modVersion() + ", so install that version of AutoModpack for " + Environment.current().loader() + " to join."));
				case LOADER_MISMATCH -> disconnect(handler, VersionedText.literal("This server does not accept your mod loader. Join with " + Environment.current().loader() + "."));
				case GENERATING -> disconnect(handler, VersionedText.literal("AutoModpack is generating modpack. Please wait a moment and try again."));
				default -> {
					disconnect(handler, VersionedText.literal("[AutoModpack] Host server error. Please contact server administrator to check the server logs!"));
					LOGGER.error("AutoModpack connection failed. Check the advertised endpoint and server configuration.");
					if (ServerConfigStore.current().disableInternalTLS)
						LOGGER.warn("Internal TLS termination is disabled; clients still use TLS and a compatible terminator must forward decrypted traffic to AutoModpack.");
					LOGGER.warn("Verify advertisedEndpointHost and advertisedEndpointPort, including any proxy or external routing configuration.");
					String fingerprint = Hosting.server().getCertificateFingerprint();
					if (fingerprint != null) LOGGER.warn("Server certificate fingerprint: {}", fingerprint);
				}
			}
		} catch (Exception e) {
			LOGGER.error("Error while handling AutoModpack connect response", e);
			disconnect(handler, VersionedText.literal("[AutoModpack] Your modpack verification response was unreadable. Reconnect, and if it repeats ask the server administrator to check the server log."));
		}
	}

	private static void handleLegacyHandshakeResponse(ServerLoginPacketListenerImpl handler, boolean understood) {
		try {
			GameProfile profile = resolveProfile(handler);
			String playerName = GameHelpers.getPlayerName(profile);

			if (!understood) {
				Common.players.put(playerName, false);
				if (ServerConfigStore.current().validateSecrets) {
					SecretsStore.removeHostSecret(GameHelpers.getPlayerUUID(profile).toString());
				}
				LOGGER.warn("{} has not installed AutoModpack.", playerName);
				if (ServerConfigStore.current().requireModpack && !nothingToServeHere()) disconnect(handler, VersionedText.literal(ServerConfigStore.current().nagMessage));
				return;
			}

			Common.players.put(playerName, true);
			disconnect(handler, VersionedText
					.literal("AutoModpack version mismatch. This server runs " + Environment.current().modVersion() + ", so install that version of AutoModpack for " + Environment.current().loader() + " to join."));
		} catch (Exception e) {
			LOGGER.error("Error while handling legacy AutoModpack handshake", e);
			disconnect(handler, VersionedText.literal("[AutoModpack] The server failed to process your handshake. Ask the server administrator to check the server log."));
		}
	}

	private static GameProfile resolveProfile(ServerLoginPacketListenerImpl handler) {
		GameProfile profile = ((ServerLoginNetworkHandlerAccessor) handler).getGameProfile();
		String playerName = GameHelpers.getPlayerName(profile);
		if (playerName == null) throw new IllegalStateException("Player name is null");
		if (GameHelpers.getPlayerUUID(profile) == null) {
			UUID offlineUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
			return new GameProfile(offlineUUID, playerName);
		}
		return profile;
	}

	private static boolean nothingToServeHere() {
		PackAbsence absence = Hosting.role().absence();
		return absence != null && Hosting.server().endpointOwner() == NettyServer.EndpointOwner.BUILT_IN;
	}

	private static void warnUnknownAcceptedLoaders() {
		if (ServerConfigStore.current().acceptedLoaders == null) return;
		for (String loader : ServerConfigStore.current().acceptedLoaders) {
			if (loader == null || loader.isBlank() || knownLoader(loader)) continue;
			LOGGER.warn("Unknown accepted-loader '{}'; it will never match", loader);
		}
	}

	private static boolean knownLoader(String loader) {
		for (LoaderManagerService.ModPlatform type : LoaderManagerService.ModPlatform.values())
			if (type.name().toLowerCase(Locale.ROOT).equals(loader.toLowerCase(Locale.ROOT))) return true;
		return Environment.current().loader() != null && Environment.current().loader().equalsIgnoreCase(loader);
	}

	private static void disconnect(ServerLoginPacketListenerImpl handler, Component reason) {
		Connection connection = ((ServerLoginNetworkHandlerAccessor) handler).getConnection();
		connection.send(new ClientboundLoginDisconnectPacket(reason));
		connection.disconnect(reason);
	}
}
