package pl.skidam.automodpack.forge.networking.packet;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.listener.ClientLoginPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraftforge.network.NetworkEvent;
import pl.skidam.automodpack.forge.networking.ModPackets;
import pl.skidam.automodpack.mixin.ServerLoginNetworkHandlerAccessor;

import java.util.function.Supplier;

import static pl.skidam.automodpack.StaticVariables.LOGGER;
import static pl.skidam.automodpack.StaticVariables.VERSION;

public class LoginS2CPacket implements Packet<ClientLoginPacketListener> {
    private final String version;
    public LoginS2CPacket(String version) {
        this.version = version;
    }

    public LoginS2CPacket(PacketByteBuf buf) {
        this.version = buf.readString();
    }

    public void write(PacketByteBuf buf) {
        buf.writeString(this.version);
    }

    public void apply(ClientLoginPacketListener listener) {

        // This code runs on client

        LOGGER.error("Received login packet from server! " + version);
        LOGGER.error("Sending login packet to server! " + VERSION);
        ClientConnection connection = ((ServerLoginNetworkHandlerAccessor) listener).getConnection();
        connection.send(new LoginC2SPacket(VERSION));
        LOGGER.error("Sent login packet to server! " + VERSION);
    }

    public void apply(Supplier<NetworkEvent.Context> supplier) {
        // This code runs on CLIENT
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ModPackets.sendToServer(new LoginC2SPacket(VERSION));
        });
    }
}