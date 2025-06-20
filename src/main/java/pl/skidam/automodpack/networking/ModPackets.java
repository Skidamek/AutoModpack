package pl.skidam.automodpack.networking;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import pl.skidam.automodpack.networking.client.ClientLoginNetworking;
import pl.skidam.automodpack.networking.content.HandshakePacket;
import pl.skidam.automodpack.networking.packet.HandshakeC2SPacket;
import pl.skidam.automodpack.networking.packet.HandshakeS2CPacket;
import pl.skidam.automodpack.networking.packet.DataC2SPacket;
import pl.skidam.automodpack.networking.packet.DataS2CPacket;
import pl.skidam.automodpack.networking.server.ServerLoginNetworking;


import java.net.InetSocketAddress;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class ModPackets {
    public static final ResourceLocation HANDSHAKE = LoginNetworkingIDs.getIdentifier(LoginNetworkingIDs.HANDSHAKE);
    public static final ResourceLocation DATA = LoginNetworkingIDs.getIdentifier(LoginNetworkingIDs.DATA);

    private static InetSocketAddress originalServerAddress;

    public static void setOriginalServerAddress(InetSocketAddress address) {
        originalServerAddress = address;
    }

    public static InetSocketAddress getOriginalServerAddress() {
        return originalServerAddress;
    }

    public static void registerC2SPackets() {
        ClientLoginNetworking.registerGlobalReceiver(HANDSHAKE, HandshakeC2SPacket::receive);
        ClientLoginNetworking.registerGlobalReceiver(DATA, DataC2SPacket::receive);

        // For single player to work, also need to register server side packets
        registerS2CPackets();
    }

    public static void registerS2CPackets() {
        ServerLoginNetworking.registerGlobalReceiver(HANDSHAKE, HandshakeS2CPacket::receive);
        ServerLoginNetworking.registerGlobalReceiver(DATA, DataS2CPacket::receive);
    }

    // Fires just after client go into login state and before any FML packet is sent.
    public static void onReady(ServerLoginPacketListenerImpl handler, MinecraftServer server, ServerLoginNetworking.LoginSynchronizer synchronizer, PacketSender sender) {
        synchronizer.waitFor(server.submit(() -> {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());

            HandshakePacket handshakePacket = new HandshakePacket(serverConfig.acceptedLoaders, AM_VERSION, MC_VERSION);
            String jsonHandshakePacket = handshakePacket.toJson();

            buf.writeUtf(jsonHandshakePacket, Short.MAX_VALUE);
            sender.sendPacket(HANDSHAKE, buf);
        }));
    }
}
