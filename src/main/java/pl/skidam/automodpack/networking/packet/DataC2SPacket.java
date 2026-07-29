package pl.skidam.automodpack.networking.packet;

import static pl.skidam.automodpack_core.Constants.*;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;

import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack.networking.client.ClientLoginDisconnect;
import pl.skidam.automodpack.networking.content.DataPacket;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public class DataC2SPacket {
	public static CompletableFuture<FriendlyByteBuf> receive(Minecraft client, ClientHandshakePacketListenerImpl handler, FriendlyByteBuf buf) {
		DataPacket dataPacket;
		try {
			String serverResponse = buf.readUtf(Short.MAX_VALUE);
			dataPacket = DataPacket.fromJson(serverResponse);
		} catch (Exception e) {
			LOGGER.error("Error parsing data packet", e);
			FriendlyByteBuf error = new FriendlyByteBuf(Unpooled.buffer());
			error.writeUtf("null", Short.MAX_VALUE);
			return CompletableFuture.completedFuture(error);
		}

		String packetEndpointHost = dataPacket.endpointHost == null ? "" : dataPacket.endpointHost;
		int packetEndpointPort = dataPacket.endpointPort;
		Secrets.Secret secret = dataPacket.secret;
		boolean modRequired = dataPacket.modRequired;
		ModpackConnectionMode connectionMode = dataPacket.connectionMode;
		if (connectionMode == null) {
			LOGGER.error("Server did not provide an AutoModpack connection mode");
			return CompletableFuture.completedFuture(buildResponse(null));
		}

		if (modRequired) {
			// TODO set screen to refreshed danger screen which will ask user to install modpack with two options
			// 1. Disconnect and install modpack
			// 2. Dont disconnect and join server
		}

		ModPackets.ConnectionAttempt connectionAttempt = ModPackets.getConnectionAttempt();
		if (connectionAttempt == null) {
			LOGGER.error("Server address is null! Something gone very wrong! Please report this issue! https://github.com/Skidamek/AutoModpack/issues");
			return CompletableFuture.completedFuture(buildResponse(null));
		}

		Jsons.ConnectionInfo connectionInfo;
		try {
			// Get actual address of the server client have connected to and format it.
			// Transports such as e4mc expose their own SocketAddress implementation, thus we need to fallback.
			var remoteAddress = ((ClientLoginNetworkHandlerAccessor) handler).getConnection().getRemoteAddress();
			InetSocketAddress connectedAddress = remoteAddress instanceof InetSocketAddress inetAddress ? inetAddress : connectionAttempt.origin();
			String effectiveHost;
			int effectivePort;

			// A blank packet endpoint uses the hostname from the established Minecraft connection.
			// This preserves hostname-routed tunnels and shared frontends; literal-IP PTR prevention happens in the resolver.
			if (packetEndpointHost.isBlank()) {
				effectiveHost = connectedAddress.getHostString();
			} else {
				effectiveHost = packetEndpointHost;
			}

			if (packetEndpointPort == -1) {
				effectivePort = connectedAddress.getPort();
			} else {
				effectivePort = packetEndpointPort;
			}

			InetSocketAddress endpoint = AddressHelpers.format(effectiveHost, effectivePort);

			LOGGER.info("AutoModpack endpoint: {}:{} ({})", endpoint.getHostString(), endpoint.getPort(), connectionMode);

			connectionInfo = new Jsons.ConnectionInfo(connectionAttempt.origin(), endpoint, connectionMode, connectionAttempt.expectedFingerprint(), connectionAttempt.trustReason());
		} catch (Exception e) {
			LOGGER.error("Error preparing AutoModpack endpoint from data packet", e);
			return CompletableFuture.completedFuture(buildResponse(null));
		}

		return ModpackUtils.requestServerModpackContentAsync(connectionInfo, secret, true).thenApplyAsync(manifestResult -> {
			if (manifestResult.state() == ModpackUtils.ManifestFetchState.OPERATION_FAILED) return buildResponse(true);
			if (!manifestResult.successful()) return buildResponse(null);

			DownloadClient downloadClient = manifestResult.client();
			SelectedModpackTarget selectedTarget;
			try {
				selectedTarget = SelectedModpackTarget.prepare(manifestResult.content(), new ClientSelectionStore(clientSelectionFile), ClientPlatform.current());
			} catch (RuntimeException e) {
				downloadClient.close();
				LOGGER.error("Failed to resolve the server modpack catalogue and group selection", e);
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
				disconnectImmediately(handler);
				return buildResponse(true);
			}
			Jsons.ModpackContentFields serverModpackContent = selectedTarget.flatTarget();
			Path modpackDir = ModpackUtils.getModpackPath(serverModpackContent.modpackId);
			try {
				SecretsStore.saveClientSecret(connectionInfo.origin, secret);
			} catch (Exception e) {
				downloadClient.close();
				LOGGER.error("Failed to persist client secret", e);
				new ScreenManager().error("automodpack.error.critical", "Failed to persist client secret", "automodpack.error.logs");
				disconnectImmediately(handler);
				return buildResponse(true);
			}

			ModpackUpdater updater = new ModpackUpdater(selectedTarget, connectionInfo, secret, modpackDir, downloadClient);
			try {
				ModpackUtils.UpdateCheckResult updateCheckResult = ModpackUtils.isUpdate(serverModpackContent, modpackDir);
				if (updateCheckResult.requiresUpdate()) {
					new ScreenManager().waiting();
					disconnectImmediately(handler);
					updater.processModpackUpdate(updateCheckResult);
					return buildResponse(true);
				}

				UpdateType restartType = updater.reconcileReceivedManifest();
				if (restartType == null) return buildResponse(false);

				new ScreenManager().waiting();
				disconnectImmediately(handler);
				new ReLauncher(modpackDir, restartType, null).restart(false);
				return buildResponse(true);
			} catch (UpdateDeferredException e) {
				updater.close();
				LOGGER.warn("Update transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
				new ScreenManager().waiting();
				disconnectImmediately(handler);
				new ReLauncher(modpackDir, UpdateType.UPDATE, null).restart(false);
				return buildResponse(true);
			} catch (Exception e) {
				updater.close();
				LOGGER.error("Failed to reconcile stable modpack installation", e);
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
				disconnectImmediately(handler);
				return buildResponse(true);
			}
		}, DownloadClient.NET_EXECUTOR).exceptionally(e -> {
			LOGGER.error("Error while handling data packet", e);
			return buildResponse(null);
		});
	}

	private static FriendlyByteBuf buildResponse(Boolean needsDisconnecting) {
		FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
		if (needsDisconnecting != null) {
			response.writeUtf(String.valueOf(needsDisconnecting), Short.MAX_VALUE);
		} else {
			response.writeUtf("null", Short.MAX_VALUE);
		}
		return response;
	}

	private static void disconnectImmediately(ClientHandshakePacketListenerImpl clientLoginNetworkHandler) {
		ClientLoginDisconnect.disconnect(clientLoginNetworkHandler);
	}
}
