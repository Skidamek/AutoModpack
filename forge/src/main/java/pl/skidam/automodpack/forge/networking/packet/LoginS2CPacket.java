package pl.skidam.automodpack.forge.networking.packet;

import net.minecraft.network.Packet;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.listener.ClientLoginPacketListener;
import net.minecraftforge.network.NetworkEvent;
import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.forge.networking.ModPackets;

import java.util.function.Supplier;

public class LoginS2CPacket implements Packet<ClientLoginPacketListener>  {
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

        AutoModpack.LOGGER.error("Received login packet from server! " + version);
        AutoModpack.LOGGER.error("Sending login packet to server! " + AutoModpack.VERSION);
        listener.getConnection().send(new LoginC2SPacket(AutoModpack.VERSION));
        AutoModpack.LOGGER.error("Sent login packet to server! " + AutoModpack.VERSION);
    }

    public void apply(Supplier<NetworkEvent.Context> supplier) {
        // This code runs on CLIENT
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ModPackets.sendToServer(new LoginC2SPacket(AutoModpack.VERSION));
        });
    }
}