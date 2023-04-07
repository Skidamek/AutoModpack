package pl.skidam.automodpack.forge.networking;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import pl.skidam.automodpack.forge.networking.packet.LinkS2CPacket;
import pl.skidam.automodpack.forge.networking.packet.LoginC2SPacket;
import pl.skidam.automodpack.forge.networking.packet.LoginS2CPacket;

import static pl.skidam.automodpack.StaticVariables.MOD_ID;

public class ModPackets {
    private static SimpleChannel INSTANCE;

    private static int packetId = 0;

    private static int id() {
        return packetId++;
    }

    public static void register() {
        SimpleChannel net = NetworkRegistry.ChannelBuilder
                .named(new Identifier(MOD_ID, "packets"))
                .networkProtocolVersion(() -> "1")
                .clientAcceptedVersions(s -> true)
                .serverAcceptedVersions(s -> true)
                .simpleChannel();

        INSTANCE = net;

        // Where on forge can I use LOGIN_TO_CLIENT ?
        net.messageBuilder(LoginS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(LoginS2CPacket::new)
                .encoder(LoginS2CPacket::write)
                .consumerMainThread(LoginS2CPacket::apply)
                .add();

        net.messageBuilder(LoginC2SPacket.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(LoginC2SPacket::new)
                .encoder(LoginC2SPacket::write)
                .consumerMainThread(LoginC2SPacket::apply)
                .add();

        net.messageBuilder(LinkS2CPacket.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(LinkS2CPacket::new)
                .encoder(LinkS2CPacket::write)
                .consumerMainThread(LinkS2CPacket::apply)
                .add();
    }

    public static <PACKET> void sendToServer(PACKET packet) {
        INSTANCE.sendToServer(packet);
    }

    public static <PACKET> void sendToClient(PACKET packet, ServerPlayerEntity player) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
