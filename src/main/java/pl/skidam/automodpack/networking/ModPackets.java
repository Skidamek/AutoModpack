package pl.skidam.automodpack.networking;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import pl.skidam.automodpack.networking.client.ClientLoginNetworking;
import pl.skidam.automodpack.networking.packet.ConnectC2SPacket;
import pl.skidam.automodpack.networking.packet.ConnectS2CPacket;
import pl.skidam.automodpack.networking.server.ServerLoginNetworking;

public class ModPackets {
	public static final Identifier HANDSHAKE = LoginNetworkingIDs.getResourceLocation(LoginNetworkingIDs.HANDSHAKE);
	public static final Identifier CONNECT = LoginNetworkingIDs.getResourceLocation(LoginNetworkingIDs.CONNECT);

	public record ConnectionAttempt(InetSocketAddress origin, String expectedFingerprint) {}

	private static ConnectionAttempt connectionAttempt;
	private static final AtomicBoolean loginQueryArrived = new AtomicBoolean();

	public static void setConnectionAttempt(ConnectionAttempt attempt) {
		connectionAttempt = attempt;
		loginQueryArrived.set(false);
	}

	public static ConnectionAttempt getConnectionAttempt() {
		return connectionAttempt;
	}

	public static void markLoginQueryArrived() {
		loginQueryArrived.set(true);
	}

	public static boolean loginQueryArrived() {
		return loginQueryArrived.get();
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
			ClientLoginNetworking.registerGlobalReceiver(CONNECT, ConnectC2SPacket::receive);
		}
	}

	public static void registerS2CPackets() {
		ServerLoginNetworking.registerGlobalReceiver(CONNECT, ConnectS2CPacket::receive);
		ServerLoginNetworking.registerGlobalReceiver(HANDSHAKE, ConnectS2CPacket::receiveLegacyHandshake);
	}

	public static void onReady(ServerLoginPacketListenerImpl handler, MinecraftServer server, ServerLoginNetworking.LoginSynchronizer synchronizer, PacketSender sender) {
		ConnectS2CPacket.sendOnReady(handler, server, synchronizer, sender);
	}
}
