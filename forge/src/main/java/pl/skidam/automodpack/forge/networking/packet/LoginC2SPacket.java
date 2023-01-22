package pl.skidam.automodpack.forge.networking.packet;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.listener.ServerLoginPacketListener;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.DisconnectS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraftforge.network.NetworkEvent;
import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.Platform;
import pl.skidam.automodpack.forge.networking.ModPackets;
import pl.skidam.automodpack.modpack.Modpack;
import pl.skidam.automodpack.utils.Ip;

import java.util.function.Supplier;

public class LoginC2SPacket implements Packet<ServerLoginPacketListener>  {
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
        ClientConnection connection = listener.getConnection();
        if (!this.version.equals(AutoModpack.VERSION)) {
            Text reason = Text.of("AutoModpack version mismatch! Install " + AutoModpack.VERSION + " version of AutoModpack mod to play on this server!");
            connection.send(new LoginDisconnectS2CPacket(reason));
            connection.disconnect(reason);
        } else {
            AutoModpack.LOGGER.info("AutoModpack version match!");

            String playerIp = connection.getAddress().toString();
            String HostIPForLocal = AutoModpack.serverConfig.hostLocalIp.substring(AutoModpack.serverConfig.hostLocalIp.indexOf("/") + 2); // Removes HTTP:// or HTTPS://
            String HostNetwork = HostIPForLocal.substring(0, HostIPForLocal.indexOf('.', HostIPForLocal.indexOf('.') + 1) + 1); // Reduces ip from x.x.x.x to x.x.

            String linkToSend;

            AutoModpack.LOGGER.error("Player IP: " + playerIp + " Is local? " + connection.isLocal());

            if (connection.isLocal() || playerIp.contains("127.0.0.1") || playerIp.contains(Ip.getLocal()) || playerIp.startsWith("/" + HostNetwork) || playerIp.contains(Ip.getPublic())) {
                linkToSend = AutoModpack.serverConfig.hostLocalIp + ":" + AutoModpack.serverConfig.hostPort; // TODO
            } else {
                linkToSend = AutoModpack.serverConfig.hostIp + ":" + AutoModpack.serverConfig.hostPort;
            }

            connection.send(new LinkS2CPacket(linkToSend));
            AutoModpack.LOGGER.warn("Sent link packet to client! " + linkToSend);
            // login success

            // TODO wait for client to send if they have modpack installed
            // TODO kick, client will install
            // TODO if client has modpack installed, login success
        }
    }

    public void apply(Supplier<NetworkEvent.Context> supplier) {
        // This code runs on SERVER

        NetworkEvent.Context context = supplier.get();
        ClientConnection connection = context.getNetworkManager();
        ServerPlayerEntity player = context.getSender();
        context.enqueueWork(() -> {
            if (!this.version.equals(AutoModpack.VERSION)) {
                Text reason = Text.of("AutoModpack version mismatch! Install " + AutoModpack.VERSION + " version of AutoModpack mod for " + Platform.getPlatformType().toString().toLowerCase() + " to play on this server!");
                connection.send(new DisconnectS2CPacket(reason));
                connection.disconnect(reason);
            } else {
                AutoModpack.LOGGER.info("AutoModpack version match!");

                if (!Modpack.Host.isRunning) return;

                String playerIp = connection.getAddress().toString();
                String HostIPForLocal = AutoModpack.serverConfig.hostLocalIp.substring(AutoModpack.serverConfig.hostLocalIp.indexOf("/") + 2); // Removes HTTP:// or HTTPS://
                String HostNetwork = HostIPForLocal.substring(0, HostIPForLocal.indexOf('.', HostIPForLocal.indexOf('.') + 1) + 1); // Reduces ip from x.x.x.x to x.x.

                String linkToSend;

                // TODO delete this
                AutoModpack.LOGGER.warn("DEBUG Player IP: " + playerIp + " Is local? " + connection.isLocal());

                if (connection.isLocal() || playerIp.contains("127.0.0.1") || playerIp.contains(Ip.getLocal()) || playerIp.startsWith("/" + HostNetwork) || playerIp.contains(Ip.getPublic())) {
                    linkToSend = AutoModpack.serverConfig.hostLocalIp + ":" + AutoModpack.serverConfig.hostPort; // TODO
                } else {
                    linkToSend = AutoModpack.serverConfig.hostIp + ":" + AutoModpack.serverConfig.hostPort;
                }

                ModPackets.sendToClient(new LinkS2CPacket(linkToSend), player);

                // login success

                // TODO wait for client to send if they have modpack installed
                //  kick, client will install
                //  if client has modpack installed, login success
            }
        });
    }
}
