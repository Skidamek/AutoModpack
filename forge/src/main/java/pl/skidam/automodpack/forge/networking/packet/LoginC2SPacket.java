package pl.skidam.automodpack.forge.networking.packet;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.listener.ServerLoginPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraftforge.network.NetworkEvent;
import pl.skidam.automodpack.mixin.ServerLoginNetworkHandlerAccessor;

import java.util.function.Supplier;

public class LoginC2SPacket implements Packet<ServerLoginPacketListener> {
    private final String version;
    public LoginC2SPacket(String version) {
        this.version = version;
    }

    public LoginC2SPacket(PacketByteBuf buf) {
        this.version = buf.readString();
    }

    public void write(PacketByteBuf buf) {
        buf.writeString(this.version);
    }

    public void apply(ServerLoginPacketListener listener) {
        ClientConnection connection = ((ServerLoginNetworkHandlerAccessor) listener).getConnection();

    }

    public void apply(Supplier<NetworkEvent.Context> supplier) {
        // This code runs on SERVER

    }
}
