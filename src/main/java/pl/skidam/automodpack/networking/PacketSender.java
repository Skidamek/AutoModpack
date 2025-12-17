package pl.skidam.automodpack.networking;

import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.resources.Identifier;

// credits to fabric api
public interface PacketSender {

    /**
     * Makes a packet for a channel.
     *
     * @param channelName the id of the channel
     * @param buf     the content of the packet
     */
    ClientboundCustomQueryPacket createPacket(Identifier channelName, FriendlyByteBuf buf);

    /**
     * Sends a packet.
     *
     * @param packet the packet
     */
    void sendPacket(ClientboundCustomQueryPacket packet);

    /**
     * Sends a packet to a channel.
     *
     * @param channel the id of the channel
     * @param buf the content of the packet
     */
    default void sendPacket(Identifier channel, FriendlyByteBuf buf) {
        Objects.requireNonNull(channel, "Channel cannot be null");
        Objects.requireNonNull(buf, "Payload cannot be null");

        this.sendPacket(this.createPacket(channel, buf));
    }
}
