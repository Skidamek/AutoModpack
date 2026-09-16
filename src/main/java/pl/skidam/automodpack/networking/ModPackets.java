package pl.skidam.automodpack.networking;

import static pl.skidam.automodpack_core.Constants.*;

import java.net.InetSocketAddress;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import pl.skidam.automodpack.networking.client.ClientLoginNetworking;
import pl.skidam.automodpack.networking.content.HandshakePacket;
import pl.skidam.automodpack.networking.packet.DataC2SPacket;
import pl.skidam.automodpack.networking.packet.DataS2CPacket;
import pl.skidam.automodpack.networking.packet.HandshakeC2SPacket;
import pl.skidam.automodpack.networking.packet.HandshakeS2CPacket;
import pl.skidam.automodpack.networking.server.ServerLoginNetworking;

public class ModPackets {
	public static final Identifier HANDSHAKE = LoginNetworkingIDs.getResourceLocation(LoginNetworkingIDs.HANDSHAKE);
	public static final Identifier DATA = LoginNetworkingIDs.getResourceLocation(LoginNetworkingIDs.DATA);

	public record ConnectionAttempt(InetSocketAddress origin, String expectedFingerprint, String trustReason) {}

	private static ConnectionAttempt connectionAttempt;

	public static void setConnectionAttempt(ConnectionAttempt attempt) {
		connectionAttempt = attempt;
	}

	public static ConnectionAttempt getConnectionAttempt() {
		return connectionAttempt;
	}

	public static void registerC2SPackets() {
		// Client registration lives in a dedicated holder class so that loading or verifying
		// ModPackets on a dedicated server can never eagerly resolve net.minecraft.client types:
		// the holder is only classloaded when this method actually runs on a client, and it is the
		// only server-reachable class allowed to reference client-only packet handlers.
		ClientPacketRegistration.register();
		registerS2CPackets();
	}

	private static class ClientPacketRegistration {
		static void register() {
			ClientLoginNetworking.registerGlobalReceiver(HANDSHAKE, HandshakeC2SPacket::receive);
			ClientLoginNetworking.registerGlobalReceiver(DATA, DataC2SPacket::receive);
		}
	}

	public static void registerS2CPackets() {
		ServerLoginNetworking.registerGlobalReceiver(HANDSHAKE, HandshakeS2CPacket::receive);
		ServerLoginNetworking.registerGlobalReceiver(DATA, DataS2CPacket::receive);
	}

	// Fires just after client go into login state and before any FML packet is sent.
	public static void onReady(ServerLoginPacketListenerImpl handler, MinecraftServer server, ServerLoginNetworking.LoginSynchronizer synchronizer,
			PacketSender sender) {
		synchronizer.waitFor(server.submit(() -> {
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());

			HandshakePacket handshakePacket = new HandshakePacket(serverConfig.acceptedLoaders, AM_VERSION, MC_VERSION);
			String jsonHandshakePacket = handshakePacket.toJson();

			buf.writeUtf(jsonHandshakePacket, Short.MAX_VALUE);
			sender.sendPacket(HANDSHAKE, buf);
		}));
	}
}
