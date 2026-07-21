package pl.skidam.automodpack.networking.packet;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;

import pl.skidam.automodpack.mixin.core.ClientConnectionAccessor;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack.networking.content.DataPacket;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
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
		boolean requiresMagic = dataPacket.requiresMagic;

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
			// Get actual address of the server client have connected to and format it
			InetSocketAddress connectedAddress = (InetSocketAddress) ((ClientLoginNetworkHandlerAccessor) handler).getConnection().getRemoteAddress();
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

			LOGGER.info("AutoModpack endpoint: {}:{}; requires magic protocol: {}", endpoint.getHostString(), endpoint.getPort(), requiresMagic);

			connectionInfo = new Jsons.ConnectionInfo(connectionAttempt.origin(), endpoint, requiresMagic, connectionAttempt.expectedFingerprint(),
					connectionAttempt.trustReason());
		} catch (Exception e) {
			LOGGER.error("Error preparing AutoModpack endpoint from data packet", e);
			return CompletableFuture.completedFuture(buildResponse(null));
		}

		return ModpackUtils.requestServerModpackContentAsync(connectionInfo, secret, true).thenApplyAsync(optionalServerModpackContent -> {
			Boolean needsDisconnecting = null;

			if (optionalServerModpackContent.isPresent()) {
				Jsons.ModpackContentFields serverModpackContent = optionalServerModpackContent.get();
				Path modpackDir = ModpackUtils.getModpackPath(serverModpackContent.modpackId);
				try {
					SecretsStore.saveClientSecret(connectionInfo.origin, secret);
				} catch (Exception e) {
					LOGGER.error("Failed to persist client secret", e);
					disconnectImmediately(handler);
					return buildResponse(true);
				}

				ModpackUtils.UpdateCheckResult updateCheckResult = ModpackUtils.isUpdate(serverModpackContent, modpackDir);
				if (updateCheckResult.requiresUpdate()) {
					disconnectImmediately(handler);
					new ModpackUpdater(serverModpackContent, connectionInfo, secret, modpackDir).processModpackUpdate(updateCheckResult);
					needsDisconnecting = true;
				} else {
					boolean selectedModpackChanged;
					try {
						selectedModpackChanged = ModpackUtils.selectModpack(serverModpackContent.modpackId, serverModpackContent.modpackName, modpackDir, connectionInfo, Set.of());
					} catch (IOException e) {
						LOGGER.error("Failed to select stable modpack installation", e);
						disconnectImmediately(handler);
						return buildResponse(true);
					}
					var modpackContentFile = modpackDir.resolve(hostModpackContentFile.getFileName());
					if (Files.exists(modpackContentFile)) {
						try {
							ModpackContentTools.write(modpackContentFile, serverModpackContent);
						} catch (IOException e) {
							LOGGER.error("Failed to save modpack content", e);
							disconnectImmediately(handler);
							return buildResponse(true);
						}
					}

					if (selectedModpackChanged) {
						disconnectImmediately(handler);
						new ReLauncher(modpackDir, UpdateType.SELECT, null).restart(false);
						needsDisconnecting = true;
					} else {
						needsDisconnecting = false;
					}
				}
			} else if (ModpackUtils.canConnectModpackHost(connectionInfo)) {
				// Couldn't download the modpack content (e.g. certificate not verified) but the host is reachable
				needsDisconnecting = true;
			}

			return buildResponse(needsDisconnecting);
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
		var channel = ((ClientConnectionAccessor) ((ClientLoginNetworkHandlerAccessor) clientLoginNetworkHandler).getConnection()).getChannel();
		channel.disconnect();
	}
}
