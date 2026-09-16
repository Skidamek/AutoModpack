package pl.skidam.automodpack.networking.packet;

import static pl.skidam.automodpack_core.Constants.*;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;

import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack.networking.content.DataPacket;
import pl.skidam.automodpack.networking.content.LoginUpdateResponse;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;

public class DataC2SPacket {
	public static CompletableFuture<FriendlyByteBuf> receive(Minecraft client, ClientHandshakePacketListenerImpl handler, FriendlyByteBuf buf) {
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

			connectionInfo = new ConnectionJsons.ConnectionInfo(connectionAttempt.origin(), endpoint, connectionMode, connectionAttempt.expectedFingerprint(), connectionAttempt.trustReason());
		} catch (Exception e) {
			LOGGER.error("Error preparing AutoModpack endpoint from data packet", e);
			return CompletableFuture.completedFuture(buildResponse(LoginUpdateResponse.HOST_ERROR));
		}

		ClientStorage storage = ClientStorage.fromGameDirectory(GameDirectory.current());
		return ClientLoginUpdateFlow.reconcile(handler, connectionInfo, secret, storage).thenApply(DataC2SPacket::buildResponse);
	}

	private static FriendlyByteBuf buildResponse(LoginUpdateResponse result) {
		FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
		response.writeUtf(result.wireValue(), Short.MAX_VALUE);
		return response;
	}
}
