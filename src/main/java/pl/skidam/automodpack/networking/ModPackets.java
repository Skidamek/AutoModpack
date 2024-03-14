package pl.skidam.automodpack.networking;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.util.Identifier;
import pl.skidam.automodpack.networking.client.ClientLoginNetworking;
import pl.skidam.automodpack.networking.content.HandshakePacket;
import pl.skidam.automodpack.networking.packet.HandshakeC2SPacket;
import pl.skidam.automodpack.networking.packet.HandshakeS2CPacket;
import pl.skidam.automodpack.networking.packet.DataC2SPacket;
import pl.skidam.automodpack.networking.packet.DataS2CPacket;
import pl.skidam.automodpack.networking.server.ServerLoginNetworking;


import static pl.skidam.automodpack_core.GlobalVariables.*;

public class ModPackets {
    public static final Identifier HANDSHAKE = LoginNetworkingIDs.getIdentifier(LoginNetworkingIDs.HANDSHAKE);
    public static final Identifier DATA = LoginNetworkingIDs.getIdentifier(LoginNetworkingIDs.DATA);

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
    public static void onReady(ServerLoginNetworkHandler handler, MinecraftServer server, ServerLoginNetworking.LoginSynchronizer synchronizer, PacketSender sender) {
        synchronizer.waitFor(server.submit(() -> {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

            HandshakePacket handshakePacket = new HandshakePacket(serverConfig.acceptedLoaders, AM_VERSION, MC_VERSION);
            String jsonHandshakePacket = handshakePacket.toJson();

            buf.writeString(jsonHandshakePacket, 32767);
            sender.sendPacket(HANDSHAKE, buf);
        }));
    }
}
