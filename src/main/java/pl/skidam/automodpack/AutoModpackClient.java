package pl.skidam.automodpack;

import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.util.Identifier;
import pl.skidam.automodpack.Client.StartAndCheck;
import pl.skidam.automodpack.utils.InternetConnectionCheck;

import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackMain.*;
public class AutoModpackClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {

        LOGGER.info("Initializing AutoModpack...");

        InternetConnectionCheck.InternetConnectionCheck();

        ClientLoginNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_S2C, (client, handler, buf, responseSender) -> {
            LOGGER.error("Received packet from server!");

            //VERSION_CHECK request received from server, send back own version

            buf.writeInt(123);
            LOGGER.error("Sent response to server!");
            return CompletableFuture.completedFuture(buf);
        });

        ClientLoginNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_C2S, (client, handler, buf, responseSender) -> {
            LOGGER.error("Received packet from server!");

            //VERSION_CHECK request received from server, send back own version

            buf.writeInt(123);
            LOGGER.error("Sent response to server!");
            return CompletableFuture.completedFuture(buf);
        });



        ClientPlayNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_S2C, (client, handler, buf, responseSender) -> {
            LOGGER.error("Received packet from server!");

            //VERSION_CHECK request received from server, send back own version

            client.getNetworkHandler().sendPacket(new GameJoinS2CPacket(new PacketByteBuf(Unpooled.buffer())));

            LOGGER.error("Sent response to server!");

        });

        ClientPlayNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_C2S, (client, handler, buf, responseSender) -> {
            LOGGER.error("Received packet from server!");

            client.getNetworkHandler().sendPacket(new GameJoinS2CPacket(new PacketByteBuf(Unpooled.buffer())));

            LOGGER.error("Sent response to server!");
        });

        new Thread(() -> new StartAndCheck(true)).start();
    }
}
