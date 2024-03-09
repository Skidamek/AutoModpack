package pl.skidam.automodpack.networking;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.login.LoginQueryRequestS2CPacket;
import net.minecraft.util.Identifier;

import java.util.Objects;

// credits to fabric api
public interface PacketSender {

    /**
     * Makes a packet for a channel.
     *
     * @param channelName the id of the channel
     * @param buf     the content of the packet
     */
    LoginQueryRequestS2CPacket createPacket(Identifier channelName, PacketByteBuf buf);

    /**
     * Sends a packet.
     *
     * @param packet the packet
     */
    void sendPacket(LoginQueryRequestS2CPacket packet);

    /**
     * Sends a packet to a channel.
     *
     * @param channel the id of the channel
     * @param buf the content of the packet
     */
    default void sendPacket(Identifier channel, PacketByteBuf buf) {
        Objects.requireNonNull(channel, "Channel cannot be null");
        Objects.requireNonNull(buf, "Payload cannot be null");

        this.sendPacket(this.createPacket(channel, buf));
    }
}
