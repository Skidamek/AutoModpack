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
import pl.skidam.automodpack.networking.content.DataPacket;
import pl.skidam.automodpack.networking.content.LoginUpdateResponse;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;

public class DataC2SPacket {
	private static final Logger LOGGER = LogManager.getLogger(DataC2SPacket.class);

	public static CompletableFuture<FriendlyByteBuf> receive(Minecraft client, ClientHandshakePacketListenerImpl handler, FriendlyByteBuf buf) {
		ModPackets.markLoginQueryArrived();
		DataPacket dataPacket;
		try {
			String serverResponse = buf.readUtf(Short.MAX_VALUE);
			dataPacket = DataPacket.fromJson(serverResponse);
		} catch (Exception e) {
			LOGGER.error("Error parsing data packet", e);
			return CompletableFuture.completedFuture(buildResponse(LoginUpdateResponse.HOST_ERROR));
		}

		String packetEndpointHost = dataPacket.endpointHost == null ? "" : dataPacket.endpointHost;
		int packetEndpointPort = dataPacket.endpointPort;
		Secrets.Secret secret = dataPacket.secret;
		ModpackConnectionMode connectionMode = dataPacket.connectionMode;
		if (connectionMode == null) {
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
			// Get actual address of the server client have connected to and format it.
			// Transports such as e4mc expose their own SocketAddress implementation, thus we need to fallback.
			var remoteAddress = ((ClientLoginNetworkHandlerAccessor) handler).getConnection().getRemoteAddress();
			InetSocketAddress connectedAddress = remoteAddress instanceof InetSocketAddress inetAddress ? inetAddress : connectionAttempt.origin();
			String effectiveHost;
			int effectivePort;

			// A blank packet endpoint uses the address from the established Minecraft connection. Hostname-routed
			// tunnels keep the connected hostname; an IP-literal or loopback join keeps the literal address the game
			// reached, so the modpack transport cannot re-resolve a PTR name like "localhost" to the other IP family.
			if (packetEndpointHost.isBlank()) {
				effectiveHost = AddressHelpers.endpointHost(connectedAddress, connectionAttempt.origin());
			} else {
				effectiveHost = packetEndpointHost;
			}

			if (packetEndpointPort == -1) {
				effectivePort = connectedAddress.getPort();
			} else {
				effectivePort = packetEndpointPort;
			}
			if (effectivePort < 1 || effectivePort > 65535) throw new IllegalArgumentException("Invalid modpack endpoint port: " + effectivePort);

			InetSocketAddress endpoint = AddressHelpers.format(effectiveHost, effectivePort);

			LOGGER.debug("AutoModpack endpoint: {}:{}", endpoint.getHostString(), endpoint.getPort());

			connectionInfo = new ConnectionJsons.ConnectionInfo(connectionAttempt.origin(), endpoint, connectionMode, connectionAttempt.expectedFingerprint());
		} catch (Exception e) {
			LOGGER.error("Error preparing AutoModpack endpoint from data packet", e);
			return CompletableFuture.completedFuture(buildResponse(LoginUpdateResponse.HOST_ERROR));
		}

		// The login-query handler runs on the netty event loop; storage opening and the reconcile prefix are disk work
		// and belong on the app executor, with only the already-consumed packet reads left on the loop.
		CompletableFuture<FriendlyByteBuf> reconciled = new CompletableFuture<>();
		ModpackUpdater.executor().execute(() -> {
			try {
				ClientStorage storage = ClientStorage.open(GameDirectory.current());
				ClientLoginUpdateFlow.reconcile(handler, connectionInfo, secret, storage, dataPacket.requireModpack, false)
						.thenApply(DataC2SPacket::buildResponse)
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

	private static FriendlyByteBuf buildResponse(LoginUpdateResponse result) {
		FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
		response.writeUtf(result.wireValue(), Short.MAX_VALUE);
		return response;
	}
}
