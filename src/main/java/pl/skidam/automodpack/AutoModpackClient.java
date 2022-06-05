package pl.skidam.automodpack;

import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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


        ClientPlayNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_S2C, (client, handler, buf, responseSender) -> {
            LOGGER.error("Received packet from server!");

            //VERSION_CHECK request received from server, send back own version

            client.execute(() -> {
                ClientPlayNetworking.send(AutoModpackMain.PACKET_C2S, PacketByteBufs.empty());
            });

            LOGGER.error("Sent response to server!");

        });


        new Thread(() -> new StartAndCheck(true)).start();
    }
}
