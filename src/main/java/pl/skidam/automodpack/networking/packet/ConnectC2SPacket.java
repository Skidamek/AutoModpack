package pl.skidam.automodpack.networking.packet;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;

import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack.networking.client.ClientLoginDisconnect;
import pl.skidam.automodpack.networking.content.HandshakePacket;
import pl.skidam.automodpack.networking.content.LoginUpdateResponse;
import pl.skidam.automodpack_core.Environment;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.client.AdvertiseFetcher;
import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.client.SelfUpdater;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.WireJsons;
import pl.skidam.automodpack_core.platforms.ModrinthAPI;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.SemanticVersion;

public class ConnectC2SPacket {
	private static final Logger LOGGER = LogManager.getLogger(ConnectC2SPacket.class);

	public static CompletableFuture<FriendlyByteBuf> receive(Minecraft client, ClientHandshakePacketListenerImpl handler, FriendlyByteBuf buf) {
		ModPackets.markLoginQueryArrived();
		WireJsons.ConnectDataFields connect;
		try {
			connect = WireJsons.ConnectDataFields.fromJson(buf.readUtf(Short.MAX_VALUE));
		} catch (Exception e) {
			LOGGER.error("Error parsing AutoModpack connect packet", e);
			return CompletableFuture.completedFuture(buildResponse(LoginUpdateResponse.HOST_ERROR));
		}

		String loader = Environment.current().loader();
		if (connect.accepts != null && !connect.accepts.contains(loader)) {
			LOGGER.warn("Loader mismatch. Server accepts: {}; Client: {}", connect.accepts, loader);
			return CompletableFuture.completedFuture(buildResponse(connect.require ? LoginUpdateResponse.LOADER_MISMATCH : LoginUpdateResponse.CONTINUE));
		}

		if (connect.v != WireJsons.WIRE_CONTRACT_VERSION) {
			LOGGER.warn("AutoModpack wire contract mismatch. Server: v{}, Client: v{}", connect.v, WireJsons.WIRE_CONTRACT_VERSION);
			return CompletableFuture.supplyAsync(
					() -> buildResponse(trySelfUpdate(handler, connect.amVersion, connect.mcVersion) ? LoginUpdateResponse.UPDATE_REQUIRED : LoginUpdateResponse.CONTRACT_MISMATCH),
					ModpackUpdater.executor());
		}

		if (shouldCheckSelfUpdate(connect.amVersion, connect.mcVersion)) {
			return CompletableFuture.supplyAsync(() -> trySelfUpdate(handler, connect.amVersion, connect.mcVersion), ModpackUpdater.executor())
					.thenCompose(updated -> updated ? CompletableFuture.completedFuture(buildResponse(LoginUpdateResponse.UPDATE_REQUIRED)) : continueConnect(handler, connect));
		}

		return continueConnect(handler, connect);
	}

	private static CompletableFuture<FriendlyByteBuf> continueConnect(ClientHandshakePacketListenerImpl handler, WireJsons.ConnectDataFields connect) {
		if (connect.generating) {
			return CompletableFuture.completedFuture(buildResponse(LoginUpdateResponse.GENERATING));
		}

		if (connect.route == null) {
			return CompletableFuture.completedFuture(buildResponse(LoginUpdateResponse.CONTINUE));
		}

		if (connect.route.mode == null) {
			LOGGER.error("Server did not provide an AutoModpack connection mode");
			return CompletableFuture.completedFuture(buildResponse(LoginUpdateResponse.HOST_ERROR));
		}

		ModPackets.ConnectionAttempt connectionAttempt = ModPackets.getConnectionAttempt();
		if (connectionAttempt == null) {
			LOGGER.error("Server address is null! Something gone very wrong! Please report this issue! https://github.com/Skidamek/AutoModpack/issues");
			return CompletableFuture.completedFuture(buildResponse(LoginUpdateResponse.HOST_ERROR));
		}

		if (AdvertiseFetcher.isEssentialRelayHost(connectionAttempt.origin()) && (connect.route.host == null || connect.route.host.isBlank())) {
			LOGGER.warn("Essential world hosting relay ({}) does not expose a reachable AutoModpack endpoint; ask the host for an external endpoint or use e4mc/World Host",
					connectionAttempt.origin().getHostString());
			ClientLoginDisconnect.disconnect(handler);
			ScreenManager.failure(FailureRequest.of(new IllegalStateException("Essential world hosting relay does not expose a reachable AutoModpack endpoint"), "automodpack.error.essentialRelay",
					FailureCategory.CONNECTION, FailureDestination.MULTIPLAYER, null));
			return CompletableFuture.completedFuture(buildResponse(LoginUpdateResponse.HOST_ERROR));
		}

		ConnectionJsons.ConnectionInfo connectionInfo;
		try {
			var remoteAddress = ((ClientLoginNetworkHandlerAccessor) handler).getConnection().getRemoteAddress();
			InetSocketAddress connectedAddress = remoteAddress instanceof InetSocketAddress inetAddress ? inetAddress : connectionAttempt.origin();
			connectionInfo = AdvertiseFetcher.resolveAdvertisedConnection(connectionAttempt.origin(), connectedAddress, connectionAttempt.expectedFingerprint(), connect.route);
			LOGGER.debug("AutoModpack endpoint: {}:{}", connectionInfo.endpoint.getHostString(), connectionInfo.endpoint.getPort());
		} catch (Exception e) {
			LOGGER.error("Error preparing AutoModpack endpoint from connect packet", e);
			return CompletableFuture.completedFuture(buildResponse(LoginUpdateResponse.HOST_ERROR));
		}

		Secrets.Secret secret = connect.secret;
		CompletableFuture<FriendlyByteBuf> reconciled = new CompletableFuture<>();
		ModpackUpdater.executor().execute(() -> {
			try {
				ClientStorage storage = ClientStorage.open(GameDirectory.current());
				ClientLoginUpdateFlow.reconcile(handler, connectionInfo, secret, storage, connect.require, false)
						.thenApply(ConnectC2SPacket::buildResponse)
						.whenComplete((response, error) -> {
							if (error == null) reconciled.complete(response);
							else reconciled.completeExceptionally(error);
						});
			} catch (Throwable e) {
				reconciled.completeExceptionally(e);
			}
		});
		return reconciled;
	}

	private static boolean shouldCheckSelfUpdate(String serverAMVersion, String serverMCVersion) {
		if (serverAMVersion == null || serverAMVersion.isBlank() || serverMCVersion == null || serverMCVersion.isBlank()) return false;
		if (!serverMCVersion.equals(Environment.current().mcVersion())) return false;
		return !Environment.current().modVersion().equals(serverAMVersion);
	}

	private static boolean trySelfUpdate(ClientHandshakePacketListenerImpl handler, String serverAMVersion, String serverMCVersion) {
		if (!shouldCheckSelfUpdate(serverAMVersion, serverMCVersion)) return false;
		ClientConfigJsons.ClientConfigFieldsV3 clientConfig = ClientStorage.current().clientConfig().current();
		if (clientConfig != null && !clientConfig.syncAutoModpackVersion) {
			LOGGER.warn("AutoModpack version syncing is disabled in client config. Cannot sync to server version: {}", serverAMVersion);
			return false;
		}

		LOGGER.debug("Versions mismatch. Server: {}; Client: {}", serverAMVersion, Environment.current().modVersion());
		ModrinthAPI automodpack = ModrinthAPI.getModSpecificVersion(SelfUpdater.AUTOMODPACK_ID, serverAMVersion, Environment.current().mcVersion());
		if (automodpack == null) {
			LOGGER.warn("Couldn't find {} version of automodpack for minecraft {} required by server", serverAMVersion, serverMCVersion);
			return false;
		}
		SemanticVersion semver = SemanticVersion.parse(automodpack.fileVersion());
		if (!SelfUpdater.validUpdate(semver, SemanticVersion.parse(Environment.current().modVersion()))) return false;

		LOGGER.info("Updating AutoModpack to {}", serverAMVersion);
		ScreenManager.waiting();
		ClientLoginDisconnect.disconnect(handler);
		SelfUpdater.installModVersion(automodpack);
		return true;
	}

	private static FriendlyByteBuf buildResponse(LoginUpdateResponse result) {
		FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer(1));
		response.writeByte(result.code());
		return response;
	}

	/** Disconnects cleanly on legacy (<v5.0.0-rc.8) servers' HANDSHAKE query so mismatched-contract clients never bypass requireModpack. */
	public static CompletableFuture<FriendlyByteBuf> receiveLegacyHandshake(Minecraft client, ClientHandshakePacketListenerImpl handler, FriendlyByteBuf buf) {
		ModPackets.markLoginQueryArrived();
		String serverVersion = null;
		try {
			HandshakePacket legacy = HandshakePacket.fromJson(buf.readUtf(Short.MAX_VALUE));
			if (legacy != null && legacy.amVersion != null && !legacy.amVersion.isBlank()) serverVersion = legacy.amVersion;
		} catch (Exception ignored) {
		}
		String message = serverVersion == null
				? "[AutoModpack] This server runs an older AutoModpack version that is not compatible with this client."
				: "[AutoModpack] AutoModpack version mismatch. This server runs " + serverVersion + ", which uses an older protocol than this client.";
		ClientLoginDisconnect.disconnect(handler, VersionedText.literal(message));
		return CompletableFuture.completedFuture(new FriendlyByteBuf(Unpooled.buffer()));
	}
}
