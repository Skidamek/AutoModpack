package pl.skidam.automodpack.networking.packet;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;

import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack.networking.client.ClientLoginDisconnect;
import pl.skidam.automodpack.networking.content.LoginUpdateResponse;
import pl.skidam.automodpack_core.Environment;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.client.SelfUpdater;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.WireJsons;
import pl.skidam.automodpack_core.platforms.ModrinthAPI;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;
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
			if (trySelfUpdate(handler, connect.amVersion, connect.mcVersion)) return CompletableFuture.completedFuture(buildResponse(LoginUpdateResponse.UPDATE_REQUIRED));
			return CompletableFuture.completedFuture(buildResponse(LoginUpdateResponse.CONTRACT_MISMATCH));
		}

		if (connect.amVersion != null && trySelfUpdate(handler, connect.amVersion, connect.mcVersion)) {
			return CompletableFuture.completedFuture(buildResponse(LoginUpdateResponse.UPDATE_REQUIRED));
		}

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

		ConnectionJsons.ConnectionInfo connectionInfo;
		try {
			var remoteAddress = ((ClientLoginNetworkHandlerAccessor) handler).getConnection().getRemoteAddress();
			InetSocketAddress connectedAddress = remoteAddress instanceof InetSocketAddress inetAddress ? inetAddress : connectionAttempt.origin();
			String effectiveHost = connect.route.host == null || connect.route.host.isBlank()
					? AddressHelpers.endpointHost(connectedAddress, connectionAttempt.origin())
					: connect.route.host;
			int effectivePort = connect.route.port == null ? connectedAddress.getPort() : connect.route.port;
			if (effectivePort < 1 || effectivePort > 65535) throw new IllegalArgumentException("Invalid modpack endpoint port: " + effectivePort);

			InetSocketAddress endpoint = AddressHelpers.format(effectiveHost, effectivePort);
			LOGGER.debug("AutoModpack endpoint: {}:{}", endpoint.getHostString(), endpoint.getPort());
			connectionInfo = new ConnectionJsons.ConnectionInfo(connectionAttempt.origin(), endpoint, connect.route.mode, connectionAttempt.expectedFingerprint());
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

	private static boolean trySelfUpdate(ClientHandshakePacketListenerImpl handler, String serverAMVersion, String serverMCVersion) {
		if (serverAMVersion == null || serverAMVersion.isBlank() || serverMCVersion == null || serverMCVersion.isBlank()) return false;
		ClientConfigJsons.ClientConfigFieldsV3 clientConfig = ClientStorage.current().clientConfig().current();
		if (clientConfig != null && !clientConfig.syncAutoModpackVersion) {
			LOGGER.warn("AutoModpack version syncing is disabled in client config. Cannot sync to server version: {}", serverAMVersion);
			return false;
		}
		if (!serverMCVersion.equals(Environment.current().mcVersion())) return false;
		if (Environment.current().modVersion().equals(serverAMVersion)) return false;

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
}
